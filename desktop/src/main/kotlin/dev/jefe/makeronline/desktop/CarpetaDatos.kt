package dev.jefe.makeronline.desktop

import dev.jefe.makeronline.AgentLock
import dev.jefe.makeronline.ListaUsuarios
import dev.jefe.makeronline.Rutas
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Dónde guarda la app sus datos: la sesión de Chrome, usuarios.json, descargados.txt, subidas/,
 * ajustes y errores. Es una carpeta del usuario en su ordenador, fuera de la vista:
 *   Windows: %LOCALAPPDATA%\MakerOnline Desktop   (C:\Users\<tú>\AppData\Local\MakerOnline Desktop)
 *   macOS:   ~/Library/Application Support/MakerOnline Desktop
 *   Linux:   ~/.local/share/makeronline-desktop
 * Con -Dmakeronline.datos=<carpeta> se usa otra (para pruebas). Los programas de terminal
 * siguen usando la carpeta desde la que se lanzan (Rutas.carpetaActual).
 */
object CarpetaDatos {
    /** Propiedad para usar otra carpeta de datos. */
    const val PROPIEDAD = "makeronline.datos"

    /**
     * Propiedad con la carpeta donde estaban los datos antes (la pone la tarea "run" de Gradle con
     * la raíz del repo): la pantalla Sesión ofrece traerlos de ahí.
     */
    const val PROPIEDAD_ANTERIOR = "makeronline.datosAnteriores"

    private const val NOMBRE = "MakerOnline Desktop"

    /** La carpeta de datos que usa la app. */
    fun elegida(): Path {
        val propia = System.getProperty(PROPIEDAD)
        val datos = if (!propia.isNullOrBlank()) Path.of(propia).toAbsolutePath() else porDefecto()
        // Al desinstalar o actualizar, Windows borra entera la carpeta donde está instalada la app:
        // los datos nunca pueden estar dentro (pasaría si alguien la instala en la carpeta de datos)
        val instalada = carpetaDeInstalacion()
        return if (instalada != null && datos.normalize().startsWith(instalada.normalize())) {
            instalada.resolveSibling(instalada.name + " (datos)")
        } else {
            datos
        }
    }

    /**
     * La carpeta donde está instalada la app, o null si no se ha lanzado desde la instalada
     * (por ejemplo, desde IntelliJ). El lanzador del instalador pone la ruta del .exe en jpackage.app-path.
     */
    fun carpetaDeInstalacion(): Path? = try {
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { Path.of(it).toAbsolutePath().parent }
    } catch (_: Exception) {
        null
    }

    /** La carpeta de datos de siempre en este sistema. */
    fun porDefecto(): Path {
        val sistema = System.getProperty("os.name").lowercase()
        val casa = Path.of(System.getProperty("user.home"))
        return when {
            sistema.startsWith("windows") -> {
                val local = System.getenv("LOCALAPPDATA")
                (if (local.isNullOrBlank()) casa.resolve("AppData").resolve("Local") else Path.of(local)).resolve(NOMBRE)
            }
            sistema.contains("mac") -> casa.resolve("Library").resolve("Application Support").resolve(NOMBRE)
            else -> {
                val xdg = System.getenv("XDG_DATA_HOME")
                (if (xdg.isNullOrBlank()) casa.resolve(".local").resolve("share") else Path.of(xdg)).resolve("makeronline-desktop")
            }
        }
    }

    /**
     * La carpeta de antes que sugiere la tarea "run" (la raíz del repo), si tiene datos, no es la
     * misma y la app todavía no tiene nada (así, una vez traídos, no se vuelve a ofrecer).
     */
    fun anteriorSugerida(actual: Path): Path? {
        val p = System.getProperty(PROPIEDAD_ANTERIOR)
        if (p.isNullOrBlank()) return null
        val dir = Path.of(p).toAbsolutePath()
        val ofrecer = TraerDatos.tieneDatos(dir) && !TraerDatos.mismaCarpeta(dir, actual) && !TraerDatos.tieneDatos(actual)
        return if (ofrecer) dir else null
    }
}

/**
 * Trae a la carpeta de datos de la app lo que había en otra (la del proyecto o la de la terminal):
 * la sesión de Chrome, la lista de usuarios, lo ya descargado (descargados.txt), los ajustes y las
 * carpetas de subidas. Solo copia: la carpeta de antes se queda como estaba.
 *
 * Nunca se machaca nada de la app: la lista de usuarios y descargados.txt se juntan, y lo que ya
 * existe (la sesión, una carpeta de subidas con el mismo nombre…) se deja como está.
 * Los modelos-descargados/ de la terminal no se traen: ya los tienes.
 */
object TraerDatos {

    /**
     * Lo que ha pasado.
     *
     * @property traido   lo que se ha traído, contado para la persona ("la sesión de Chrome"…)
     * @property avisos   lo que no se ha traído y por qué
     * @property sesion   si se ha traído la sesión de Chrome (entonces hay que comprobarla)
     */
    class Resultado(val traido: List<String>, val avisos: List<String>, val sesion: Boolean)

    /** Cachés de Chrome: se vuelven a crear solas y pueden ocupar cientos de MB. */
    private val CACHES = setOf(
        "Cache", "Code Cache", "GPUCache", "DawnCache", "DawnGraphiteCache", "DawnWebGPUCache",
        "GrShaderCache", "GraphiteDawnCache", "ShaderCache", "CacheStorage", "ScriptCache",
        "Crashpad", "BrowserMetrics", "component_crx_cache", "extensions_crx_cache",
        "optimization_guide_model_store",
    )

    /** Archivos que Chrome usa para saber si está abierto: no se copian. */
    private val BLOQUEOS = setOf("SingletonLock", "SingletonCookie", "SingletonSocket", "lockfile")

    /** Las carpetas de dentro de subidas/ que se traen. */
    private val SUBIDAS = listOf("pendientes", "subidos", "errores", "eliminados")

    /** Si en esa carpeta hay algo que traer. */
    fun tieneDatos(dir: Path): Boolean {
        if (!dir.isDirectory()) return false
        val r = Rutas(dir)
        return r.perfilChrome.isDirectory() || r.usuarios.isRegularFile() || r.registro.isRegularFile() ||
            r.subidas.isDirectory()
    }

    fun mismaCarpeta(a: Path, b: Path): Boolean = try {
        Files.exists(a) && Files.exists(b) && Files.isSameFile(a, b)
    } catch (_: IOException) {
        a.toAbsolutePath().normalize() == b.toAbsolutePath().normalize()
    }

    /**
     * Copia lo de [origen] a [destino]. Coge el candado de las dos carpetas: si algún programa está
     * usando Chrome con una de ellas, lanza AgenteException y no copia nada.
     */
    fun traer(origen: Path, destino: Rutas): Resultado {
        val antes = Rutas(origen.toAbsolutePath())
        if (mismaCarpeta(antes.datos, destino.datos)) throw IllegalArgumentException(Textos.TRAER_MISMA)
        if (!tieneDatos(antes.datos)) throw IllegalArgumentException(Textos.traerNada(antes.datos.toString()))
        Files.createDirectories(destino.datos)

        val traido = ArrayList<String>()
        val avisos = ArrayList<String>()
        var sesion = false
        AgentLock.acquire(antes.candado, "MakerOnline Desktop (trayendo datos)", false, ProgresoNulo).use {
            AgentLock.acquire(destino.candado, "MakerOnline Desktop (trayendo datos)", false, ProgresoNulo).use {
                // --- La sesión de Chrome ---
                if (antes.perfilChrome.isDirectory()) {
                    if (tieneAlgo(destino.perfilChrome)) {
                        avisos += Textos.TRAER_YA_HAY_SESION
                    } else {
                        try {
                            copiarPerfil(antes.perfilChrome, destino.perfilChrome)
                            traido += Textos.TRAIDO_SESION
                            sesion = true
                        } catch (e: IOException) {
                            avisos += Textos.traerSinSesion(e.message ?: e.toString())
                        }
                    }
                }
                // --- La lista de usuarios ---
                if (antes.usuarios.isRegularFile()) {
                    try {
                        val n = juntarLista(antes.usuarios, destino.usuarios)
                        if (n > 0) traido += Textos.traidosUsuarios(n)
                    } catch (e: Exception) {
                        avisos += Textos.traerSinLista(e.message ?: e.toString())
                    }
                }
                // --- Lo ya descargado ---
                if (antes.registro.isRegularFile()) {
                    val n = juntarRegistro(antes.registro, destino.registro)
                    if (n > 0) traido += Textos.traidosDescargados(n)
                }
                // --- Ajustes (solo si la app aún no tiene) ---
                if (antes.ajustes.isRegularFile() && !Files.exists(destino.ajustes)) {
                    Files.copy(antes.ajustes, destino.ajustes)
                    traido += Textos.TRAIDOS_AJUSTES
                }
                // --- Subidas: cada carpeta de modelo que la app no tenga ---
                var carpetas = 0
                var repetidas = 0
                for (sub in SUBIDAS) {
                    val de = antes.subidas.resolve(sub)
                    if (!de.isDirectory()) continue
                    for (modelo in hijos(de).filter { it.isDirectory() }) {
                        val a = destino.subidas.resolve(sub).resolve(modelo.name)
                        if (Files.exists(a)) {
                            repetidas++
                        } else {
                            copiarArbol(modelo, a) { false }
                            carpetas++
                        }
                    }
                }
                if (carpetas > 0) traido += Textos.traidasSubidas(carpetas)
                if (repetidas > 0) avisos += Textos.subidasRepetidas(repetidas)
                // --- Lo que quedara a medias en la app de antes (descargas sin zip y sus errores) ---
                for ((de, a) in listOf(antes.descargaEnCurso to destino.descargaEnCurso, antes.erroresDescargas to destino.erroresDescargas)) {
                    if (de.isDirectory()) copiarArbol(de, a, sinMachacar = true) { false }
                }
            }
        }
        return Resultado(traido, avisos, sesion)
    }

    private fun tieneAlgo(dir: Path): Boolean = dir.isDirectory() && Files.list(dir).use { it.findAny().isPresent }

    /**
     * Copia el perfil de Chrome sin cachés ni archivos de bloqueo. Se copia primero a una carpeta
     * aparte y se renombra al final: si falla a medias, la app no se queda con un perfil roto.
     */
    private fun copiarPerfil(de: Path, a: Path) {
        val temporal = a.resolveSibling(a.name + ".trayendo")
        borrar(temporal)
        try {
            copiarArbol(de, temporal) { p -> p.name in CACHES || p.name in BLOQUEOS }
            borrar(a) // si había una carpeta vacía
            Files.move(temporal, a)
        } catch (e: IOException) {
            borrar(temporal)
            throw e
        }
    }

    /** Añade a la lista de la app los usuarios de la de antes que no estén. Devuelve cuántos. */
    private fun juntarLista(de: Path, a: Path): Int {
        val antes = ListaUsuarios.leer(de)
        if (antes.error != null) throw IllegalStateException(antes.error)
        if (!Files.exists(a)) {
            // Tal cual, con sus comentarios
            Files.copy(de, a)
            return antes.entradas.size
        }
        val ahora = ListaUsuarios.leer(a).entradas
        var n = 0
        for (e in antes.entradas) {
            if (ahora.none { it.equals(e, ignoreCase = true) }) {
                ListaUsuarios.agregar(a, e)
                n++
            }
        }
        return n
    }

    /** Añade a descargados.txt de la app las líneas (por id de modelo) que no tenga. Devuelve cuántas. */
    private fun juntarRegistro(de: Path, a: Path): Int {
        val ids = if (Files.exists(a)) Files.readAllLines(a).map(::idDe).toHashSet() else HashSet()
        val nuevas = Files.readAllLines(de).filter { it.isNotBlank() && ids.add(idDe(it)) }
        if (nuevas.isEmpty()) return 0
        // Si el archivo de la app no acaba en salto de línea, se pone uno antes
        val prefijo = if (Files.exists(a) && Files.size(a) > 0 && !Files.readString(a).endsWith("\n")) "\n" else ""
        Files.writeString(a, prefijo + nuevas.joinToString("\n", postfix = "\n"),
            StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        return nuevas.size
    }

    private fun idDe(linea: String): String = linea.substringBefore('\t').trim()

    /**
     * Copia una carpeta entera. [saltar] dice qué carpetas o archivos no se copian.
     * Con [sinMachacar], lo que ya existe en el destino se deja.
     */
    private fun copiarArbol(de: Path, a: Path, sinMachacar: Boolean = false, saltar: (Path) -> Boolean) {
        Files.createDirectories(a)
        for (hijo in hijos(de)) {
            if (saltar(hijo)) continue
            val destino = a.resolve(hijo.name)
            when {
                Files.isSymbolicLink(hijo) -> continue
                hijo.isDirectory() -> copiarArbol(hijo, destino, sinMachacar, saltar)
                sinMachacar && Files.exists(destino) -> continue
                else -> Files.copy(hijo, destino, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }

    private fun borrar(dir: Path) {
        if (!Files.exists(dir)) return
        Files.walk(dir).use { s -> s.sorted(Comparator.reverseOrder()).toList() }.forEach { Files.deleteIfExists(it) }
    }

    private fun hijos(dir: Path): List<Path> = Files.list(dir).use { s -> s.sorted().toList() }
}
