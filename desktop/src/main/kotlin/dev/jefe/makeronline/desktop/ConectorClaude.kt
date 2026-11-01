package dev.jefe.makeronline.desktop

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Añade (o quita) el conector de la app ([ServidorMcp]) en la configuración de la aplicación de
 * Claude (claude_desktop_config.json), sin tocar nada más de ese archivo. Antes de cambiarlo
 * guarda una copia al lado (claude_desktop_config.json.bak-makeronline). Si el archivo no es JSON
 * válido, no lo toca.
 *
 * Claude lo lee al arrancar: después hay que cerrarlo del todo y abrirlo otra vez.
 */
object ConectorClaude {

    /** El nombre del conector dentro de Claude. */
    const val NOMBRE = "jujamaker"

    /** El nombre que tenía antes (cuando la app se llamaba MakerOnline Desktop): al conectar, se quita. */
    private const val NOMBRE_ANTIGUO = "makeronline"

    enum class Estado {
        /** Ya está, apuntando a esta app. */
        CONECTADO,
        /** Hay un conector de la app, pero apunta a otro .exe (otra instalación o una versión anterior). */
        OTRA_RUTA,
        NO_CONECTADO,
        /** No está la carpeta de configuración de Claude: no parece instalado. */
        SIN_CLAUDE,
    }

    private val json = ObjectMapper()

    private const val ARCHIVO = "claude_desktop_config.json"

    /**
     * Dónde guarda Claude (la aplicación de escritorio) su configuración.
     *
     * En Windows depende de cómo se instaló Claude y de su versión, así que se miran todas las
     * carpetas posibles y se usa la que Claude ha usado por última vez:
     * - `%LOCALAPPDATA%\Claude-Data\claude_…`: Claude instalado como paquete de Windows (MSIX), en
     *   las versiones que guardan sus datos fuera del paquete (comprobado con la 4.4212: es la
     *   `--user-data-dir` de sus procesos).
     * - `%APPDATA%\Claude`: el instalador clásico.
     * - `%LOCALAPPDATA%\Packages\Claude_…\LocalCache\Roaming\Claude`: paquete de Windows, versiones
     *   anteriores.
     *
     * "La última que ha usado" = la que tiene el archivo `Local State` más reciente (lo escribe Claude
     * cada vez que se abre). Si ninguna lo tiene, la primera que exista de la lista.
     */
    fun archivoConfig(): Path {
        val sistema = System.getProperty("os.name").lowercase()
        val casa = Path.of(System.getProperty("user.home"))
        return when {
            sistema.startsWith("windows") -> carpetaWindows(casa).resolve(ARCHIVO)
            sistema.contains("mac") -> casa.resolve("Library/Application Support/Claude").resolve(ARCHIVO)
            else -> casa.resolve(".config/Claude").resolve(ARCHIVO)
        }
    }

    private fun carpetaWindows(casa: Path): Path {
        val local = variable("LOCALAPPDATA") ?: casa.resolve("AppData").resolve("Local")
        val roaming = (variable("APPDATA") ?: casa.resolve("AppData").resolve("Roaming")).resolve("Claude")
        // Con add y no con "+": un Path también es una lista (de sus partes) y "+" las añadiría una a una
        val candidatas = buildList {
            addAll(hijas(local.resolve("Claude-Data"), "claude_*"))
            add(roaming)
            addAll(hijas(local.resolve("Packages"), "Claude_*").map { it.resolve("LocalCache").resolve("Roaming").resolve("Claude") })
        }
        val existentes = candidatas.filter { Files.isDirectory(it) }
        return existentes.mapNotNull { c -> fecha(c.resolve("Local State"))?.let { c to it } }.maxByOrNull { it.second }?.first
            ?: existentes.firstOrNull()
            ?: roaming
    }

    private fun variable(nombre: String): Path? = System.getenv(nombre)?.takeIf { it.isNotBlank() }?.let { Path.of(it) }

    /** Las subcarpetas de [dir] que cumplen [patron] ("Claude_*"), en orden; vacío si no hay. */
    private fun hijas(dir: Path, patron: String): List<Path> {
        if (!Files.isDirectory(dir)) return emptyList()
        return try {
            Files.newDirectoryStream(dir, patron).use { d -> d.filter { Files.isDirectory(it) }.sorted() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fecha(archivo: Path): Long? = try {
        if (Files.isRegularFile(archivo)) Files.getLastModifiedTime(archivo).toMillis() else null
    } catch (_: Exception) {
        null
    }

    /**
     * El .exe que Claude tiene que arrancar: el de la app que está abierta si es la instalada; si
     * no (desde IntelliJ), el de la instalación, si existe. Null si no hay app instalada.
     */
    fun ejecutable(): Path? {
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { return Path.of(it).toAbsolutePath() }
        val local = variable("LOCALAPPDATA") ?: return null
        // Las de abajo son de versiones anteriores, cuando la app se llamaba MakerOnline Desktop
        return listOf(
            local.resolve("JujaMaker").resolve("JujaMaker.exe"),
            local.resolve("MakerOnline Desktop App").resolve("MakerOnline Desktop.exe"),
            local.resolve("ProgramsMakerOnline Desktop").resolve("MakerOnline Desktop.exe"),
        ).firstOrNull { Files.isRegularFile(it) }
    }

    fun estado(config: Path = archivoConfig(), exe: Path? = ejecutable()): Estado {
        if (!Files.isDirectory(config.parent)) return Estado.SIN_CLAUDE
        if (!Files.exists(config)) return Estado.NO_CONECTADO
        val raiz = try {
            json.readTree(config.toFile())
        } catch (_: Exception) {
            return Estado.NO_CONECTADO
        }
        val servidores = raiz.path("mcpServers")
        val entrada = servidores.path(NOMBRE)
        if (entrada.isMissingNode || entrada.isNull) {
            // Solo el de antes (makeronline): hay que volver a conectar
            val antiguo = servidores.path(NOMBRE_ANTIGUO)
            return if (antiguo.isMissingNode || antiguo.isNull) Estado.NO_CONECTADO else Estado.OTRA_RUTA
        }
        val comando = entrada.path("command").asText("")
        return if (exe != null && comando.equals(exe.toString(), ignoreCase = true)) Estado.CONECTADO else Estado.OTRA_RUTA
    }

    /**
     * Añade o actualiza el conector apuntando a [exe]. Lo demás del archivo se queda igual.
     *
     * @throws IllegalStateException si el archivo de Claude no se puede leer como JSON (no se toca)
     */
    fun conectar(exe: Path, config: Path = archivoConfig()) {
        cambiar(config) { raiz ->
            val servidores = (raiz.get("mcpServers") as? ObjectNode) ?: raiz.putObject("mcpServers")
            servidores.remove(NOMBRE_ANTIGUO)
            servidores.putObject(NOMBRE).apply {
                put("command", exe.toString())
                putArray("args").add("--mcp")
            }
        }
    }

    /** Quita el conector (si estaba). Lo demás del archivo se queda igual. */
    fun quitar(config: Path = archivoConfig()) {
        if (!Files.exists(config)) return
        cambiar(config) { raiz ->
            val servidores = raiz.get("mcpServers") as? ObjectNode ?: return@cambiar
            servidores.remove(NOMBRE)
            servidores.remove(NOMBRE_ANTIGUO)
            if (servidores.isEmpty) raiz.remove("mcpServers")
        }
    }

    /** El comando para añadir el conector en Claude Code. */
    fun comandoClaudeCode(exe: Path): String = "claude mcp add $NOMBRE -- \"$exe\" --mcp"

    private fun cambiar(config: Path, cambio: (ObjectNode) -> Unit) {
        val raiz: ObjectNode = if (Files.exists(config) && Files.size(config) > 0) {
            val leido = try {
                json.readTree(config.toFile())
            } catch (e: Exception) {
                throw IllegalStateException(Textos.configClaudeRota(config.toString()))
            }
            leido as? ObjectNode ?: throw IllegalStateException(Textos.configClaudeRota(config.toString()))
        } else {
            json.createObjectNode()
        }
        cambio(raiz)
        Files.createDirectories(config.parent)
        if (Files.exists(config)) {
            Files.copy(config, config.resolveSibling(config.fileName.toString() + ".bak-makeronline"), StandardCopyOption.REPLACE_EXISTING)
        }
        // Se escribe aparte y se cambia por el bueno: si algo falla a medias, el de Claude no queda cortado
        val temporal = config.resolveSibling(config.fileName.toString() + ".tmp-makeronline")
        Files.writeString(temporal, json.writerWithDefaultPrettyPrinter().writeValueAsString(raiz) + "\n", Charsets.UTF_8)
        try {
            Files.move(temporal, config, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporal, config, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
