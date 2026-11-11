package dev.jefe.makeronline.desktop

import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.filechooser.FileSystemView
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

/**
 * Pasa lo que ha bajado la app a un zip en el escritorio, para no tener que buscar nada
 * dentro de la carpeta del programa.
 *
 * La app descarga en descarga-en-curso/<usuario>/<título>_<id>/ (ver Rutas.descargaEnCurso).
 * Al terminar, haya ido bien, se haya parado o haya fallado:
 *  - los modelos completos (los que tienen info.json, que el Downloader escribe lo último) van a
 *    descargas-<día-mes-año>.zip, con las mismas carpetas (las de Creality acaban en " (Creality)"); si ya existe, "… (2).zip", "… (3).zip"…
 *  - errores.txt y las capturas error_<id>.png se guardan en errores-descargas/<usuario>/,
 *    para poder verlos desde la app ("Ver detalle de errores");
 *  - descarga-en-curso se vacía. Lo que haya quedado a medias se vuelve a bajar la próxima vez,
 *    porque no se ha apuntado en descargados.txt (ni en descargados-creality.txt).
 * Si el zip no se puede crear, no se borra nada: lo descargado irá en el zip de la próxima vez.
 */
object ZipDescargas {

    /** @param zip el zip creado, o null si no había ningún modelo completo */
    class Resultado(val zip: Path?, val modelos: Int)

    private val FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy")

    /**
     * @param trabajo  la carpeta donde ha descargado la app (descarga-en-curso)
     * @param destino  donde se deja el zip (el escritorio)
     * @param errores  donde se guardan los errores (errores-descargas)
     * @throws IOException si no se ha podido crear el zip (entonces no se ha borrado nada)
     */
    fun empaquetar(trabajo: Path, destino: Path, errores: Path, hoy: LocalDate = LocalDate.now()): Resultado {
        if (!trabajo.isDirectory()) return Resultado(null, 0)
        val modelos = modelosCompletos(trabajo)
        val zip = if (modelos.isEmpty()) null else escribirZip(trabajo, modelos, destino, hoy)
        guardarErrores(trabajo, errores)
        borrar(trabajo)
        return Resultado(zip, modelos.size)
    }

    /** El escritorio de Windows (también si OneDrive lo ha movido); si no se encuentra, la carpeta personal. */
    fun escritorio(): Path {
        try {
            // En Windows, la "carpeta de inicio" de FileSystemView es el escritorio de verdad
            val f = FileSystemView.getFileSystemView().homeDirectory
            if (f != null && f.isDirectory) return f.toPath()
        } catch (_: Exception) {
            // se prueba lo de abajo
        }
        val casa = Path.of(System.getProperty("user.home"))
        return listOf("Desktop", "Escritorio").map(casa::resolve).firstOrNull { it.isDirectory() } ?: casa
    }

    /** descargas-31-10-2026.zip, o descargas-31-10-2026 (2).zip si ya existe… */
    fun nombreLibre(destino: Path, hoy: LocalDate): Path {
        val base = "descargas-" + hoy.format(FECHA)
        var n = 1
        while (true) {
            val nombre = if (n == 1) "$base.zip" else "$base ($n).zip"
            val ruta = destino.resolve(nombre)
            if (!Files.exists(ruta)) return ruta
            n++
        }
    }

    /** Las carpetas <usuario>/<modelo>/ que tienen info.json, ordenadas. */
    private fun modelosCompletos(trabajo: Path): List<Path> =
        hijos(trabajo).filter { it.isDirectory() }
            .flatMap { usuario -> hijos(usuario).filter { it.isDirectory() && it.resolve("info.json").isRegularFile() } }

    private fun escribirZip(trabajo: Path, modelos: List<Path>, destino: Path, hoy: LocalDate): Path {
        Files.createDirectories(destino)
        val zip = nombreLibre(destino, hoy)
        // Se escribe con otro nombre y se renombra al final: así nunca queda un zip a medias en el escritorio
        val temporal = zip.resolveSibling(zip.name + ".part")
        try {
            ZipOutputStream(BufferedOutputStream(Files.newOutputStream(temporal))).use { salida ->
                for (modelo in modelos) {
                    val archivos = Files.walk(modelo).use { s -> s.filter { it.isRegularFile() }.sorted().toList() }
                    for (archivo in archivos) {
                        // Dentro del zip, las rutas van con "/" (usuario/modelo/archivo)
                        salida.putNextEntry(ZipEntry(trabajo.relativize(archivo).joinToString("/") { it.name }))
                        Files.copy(archivo, salida)
                        salida.closeEntry()
                    }
                }
            }
            try {
                Files.move(temporal, zip, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporal, zip)
            }
        } catch (e: IOException) {
            try {
                Files.deleteIfExists(temporal)
            } catch (_: IOException) {
                // no es grave
            }
            throw e
        }
        return zip
    }

    /** errores.txt se añade al que ya hubiera; las capturas se mueven. */
    private fun guardarErrores(trabajo: Path, errores: Path) {
        for (usuario in hijos(trabajo).filter { it.isDirectory() }) {
            val sueltos = hijos(usuario).filter {
                it.isRegularFile() && (it.name == "errores.txt" || (it.name.startsWith("error_") && it.name.endsWith(".png")))
            }
            if (sueltos.isEmpty()) continue
            val dest = errores.resolve(usuario.name)
            Files.createDirectories(dest)
            for (archivo in sueltos) {
                if (archivo.name == "errores.txt") {
                    Files.write(dest.resolve(archivo.name), Files.readAllBytes(archivo),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)
                } else {
                    Files.move(archivo, dest.resolve(archivo.name), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** Borra la carpeta con todo lo de dentro. Si algo no se puede borrar (abierto en otro programa), se deja. */
    private fun borrar(carpeta: Path) {
        val todo = Files.walk(carpeta).use { s -> s.sorted(Comparator.reverseOrder()).toList() }
        for (p in todo) {
            try {
                Files.deleteIfExists(p)
            } catch (_: IOException) {
                // se intentará la próxima vez
            }
        }
    }

    private fun hijos(carpeta: Path): List<Path> = Files.list(carpeta).use { s -> s.sorted().toList() }
}
