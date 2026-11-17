package dev.jefe.makeronline

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Las páginas a las que se sube (pantalla Subir: un solo botón y una ficha por página). Va aparte de
 * [Web], que son las webs con sesión en la app y de las que se descarga: Printables se sube desde el
 * Chrome de siempre con la extensión y Makeroad no descarga.
 *
 * Cada una deja en la carpeta del modelo su archivo de "ya subido" ([registro]) y, si falla, su
 * archivo de error ([errorTxt]).
 *
 * **Cuándo se retira una carpeta de pendientes** (pedido por la persona el 17/11/2026): solo cuando
 * está en **todas** las páginas que se eligieron al subirla. Al pulsar Subir se apuntan en
 * `destinos.txt` dentro de la carpeta ([apuntar]); cada vez que una página termina se mira
 * [retirarSiCompleta]. Así lo que falle (o lo que falte de Printables, que va aparte con la
 * extensión) se queda en pendientes/ con sus insignias, y no se sube dos veces a ninguna.
 */
enum class Destino(val nombre: String, val registro: String, val errorTxt: String?) {
    MAKERONLINE("MakerOnline", Uploader.REGISTRO, Uploader.ERROR_TXT),
    CREALITY("Creality Cloud", SubidorCreality.REGISTRO, SubidorCreality.ERROR_TXT),
    PRINTABLES("Printables", ColaPrintables.REGISTRO, null),
    MAKEROAD("Makeroad", SubidorMakeroad.REGISTRO, SubidorMakeroad.ERROR_TXT),
    ;

    /** Si esa carpeta ya está en esta página. */
    fun estaEn(carpeta: Path): Boolean = Files.exists(carpeta.resolve(registro))

    /** El error del último intento fallido en esta página, si lo hay y no está subida. */
    fun error(carpeta: Path): Path? =
        errorTxt?.let { carpeta.resolve(it) }?.takeIf { !estaEn(carpeta) && Files.isRegularFile(it) }

    /**
     * Si la carpeta puede ir a esta página. A Creality solo se sube con .3mf: sin él, no cuenta para
     * saber si la carpeta está completa (si no, no se retiraría nunca).
     */
    fun aplica(carpeta: Path): Boolean = this != CREALITY || tiene3mf(carpeta)

    companion object {
        /** Dentro de la carpeta del modelo: las páginas elegidas al subirla, una por línea. */
        const val DESTINOS = "destinos.txt"

        /** Apunta en la carpeta a qué páginas tiene que ir (sustituye lo que hubiera). */
        @JvmStatic
        fun apuntar(carpeta: Path, destinos: Collection<Destino>) {
            Files.writeString(carpeta.resolve(DESTINOS), destinos.joinToString("\n", postfix = "\n") { it.name })
        }

        /** Las páginas apuntadas en la carpeta, o null si no tiene destinos.txt (carpetas de antes). */
        @JvmStatic
        fun leer(carpeta: Path): Set<Destino>? {
            val archivo = carpeta.resolve(DESTINOS)
            if (!Files.isRegularFile(archivo)) return null
            return try {
                Files.readAllLines(archivo).mapNotNull { l -> entries.firstOrNull { it.name == l.trim() } }.toSet()
            } catch (_: IOException) {
                null
            }
        }

        /** Las páginas apuntadas en las que todavía falta (sin contar Creality si no tiene .3mf). */
        @JvmStatic
        fun faltan(carpeta: Path): List<Destino> =
            leer(carpeta).orEmpty().filter { it.aplica(carpeta) && !it.estaEn(carpeta) }

        /**
         * Si la carpeta ya está en todas sus páginas, la pasa a subidos/ (con la fecha delante, como
         * siempre) y devuelve dónde ha quedado; si no (o no tiene destinos.txt), null.
         */
        @JvmStatic
        @JvmOverloads
        fun retirarSiCompleta(rutas: Rutas, carpeta: Path, progreso: Progreso? = null): Path? {
            val destinos = leer(carpeta) ?: return null
            if (destinos.isEmpty() || !Files.isDirectory(carpeta) || faltan(carpeta).isNotEmpty()) return null
            return try {
                Files.createDirectories(rutas.subidos)
                val destino = Uploader.mover(carpeta, rutas.subidos)
                progreso?.correcto("${carpeta.fileName}: ya está en ${destinos.joinToString(", ") { it.nombre }} → subidos/")
                destino
            } catch (e: IOException) {
                progreso?.error("${carpeta.fileName} ya está en todas sus páginas, pero no he podido moverla a subidos/: ${e.message}")
                null
            }
        }

        private fun tiene3mf(carpeta: Path): Boolean = try {
            ModelFolder.modelosDe(carpeta).any { it.fileName.toString().lowercase(Locale.ROOT).endsWith(".3mf") }
        } catch (_: IOException) {
            false
        }
    }
}
