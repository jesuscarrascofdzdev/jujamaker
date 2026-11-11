package dev.jefe.makeronline

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Sube carpetas de subidas/pendientes/ a las webs elegidas (MakerOnline, Creality Cloud, Nexprint o
 * varias), una web detrás de otra: primero Creality, luego Nexprint y al final MakerOnline.
 *
 * Por qué en ese orden: el Uploader de MakerOnline mueve cada carpeta a subidos/ (o a errores/) al
 * terminar, y las demás webs necesitan encontrarla todavía en pendientes/. Así:
 *  1. Creality sube las que aún no tiene (sin `subido-creality.txt`). No mueve nada.
 *  2. Nexprint sube las que aún no tiene (sin `subido-nexprint.txt`). No mueve nada.
 *  3. MakerOnline sube solo las que ya están en las demás webs elegidas (en Creality cuentan también
 *     las que no se pueden subir allí porque no tienen .3mf) y las mueve a subidos/ o errores/ como
 *     siempre. Si otra web falla con una carpeta, esa se queda en pendientes/ sin ir a MakerOnline:
 *     la próxima vez solo se repite lo que falta, sin subir nada dos veces.
 *  4. Sin MakerOnline, las que ya están en todas las webs elegidas pasan a subidos/.
 *
 * No coge el candado de Chrome (AgentLock): lo coge quien la llama, una vez para todo.
 */
class SubidaMultiple(
    private val rutas: Rutas,
    private val progreso: Progreso,
    private val cancelacion: Cancelacion,
    private val revision: Revision,
) {

    /**
     * @property carpetas los nombres de las carpetas de pendientes/ que se suben (al menos una)
     */
    data class Opciones(
        val makerOnline: Boolean,
        val creality: Boolean,
        val revisar: Boolean,
        val carpetas: List<String>,
        val verChrome: Boolean,
        val nexprint: Boolean = false,
    )

    /**
     * Cómo ha ido.
     *
     * @property creality       Creality (null si no se ha elegido o no había nada que subir allí)
     * @property makerOnline    MakerOnline (null si no se ha elegido o no había nada que subir allí)
     * @property sinMakerOnline las que no han ido a MakerOnline porque no están en las demás webs elegidas
     *                          (ha fallado o se ha descartado al revisar): siguen en pendientes/
     * @property sin3mf         las que no se pueden subir a Creality (solo tienen .stl)
     * @property aSubidos       sin MakerOnline: las que han pasado a subidos/
     * @property parado         si se ha parado a petición de la persona
     * @property nexprint       Nexprint (null si no se ha elegido o no había nada que subir allí)
     */
    data class Resumen(
        val creality: SubidorCreality.Resumen?,
        val makerOnline: Uploader.Resumen?,
        val sinMakerOnline: List<String>,
        val sin3mf: List<String>,
        val aSubidos: Int,
        val parado: Boolean,
        val nexprint: SubidorNexprint.Resumen? = null,
    )

    fun ejecutar(op: Opciones): Resumen {
        require(op.makerOnline || op.creality || op.nexprint) { "Elige al menos una web." }
        require(op.carpetas.isNotEmpty()) { "No hay carpetas que subir." }
        val pendientes = rutas.pendientes
        val sin3mf = if (op.creality) op.carpetas.filter { !tiene3mf(pendientes.resolve(it)) } else emptyList()

        // --- 1) Creality ---
        var rc: SubidorCreality.Resumen? = null
        if (op.creality) {
            progreso.info("── ${Web.CREALITY.nombre} ──")
            if (sin3mf.isNotEmpty()) {
                progreso.nota("A ${Web.CREALITY.nombre} de momento solo se sube con .3mf; no van: ${sin3mf.joinToString(", ")}")
            }
            val conTresmf = op.carpetas.filter { it !in sin3mf }
            if (conTresmf.isNotEmpty()) {
                rc = SubidorCreality(rutas, conWeb(Web.CREALITY), cancelacion, revisionDe(Web.CREALITY))
                    .ejecutar(SubidorCreality.Opciones(op.revisar, conTresmf, false, op.verChrome))
            }
            if (rc?.parado == true || cancelacion.pedida()) {
                return Resumen(rc, null, emptyList(), sin3mf, 0, true)
            }
        }

        // --- 2) Nexprint ---
        var rn: SubidorNexprint.Resumen? = null
        if (op.nexprint) {
            progreso.info("── ${Web.NEXPRINT.nombre} ──")
            rn = SubidorNexprint(rutas, conWeb(Web.NEXPRINT), cancelacion, revisionDe(Web.NEXPRINT))
                .ejecutar(SubidorNexprint.Opciones(op.revisar, op.carpetas, false, op.verChrome))
            if (rn.parado || cancelacion.pedida()) {
                return Resumen(rc, null, emptyList(), sin3mf, 0, true, rn)
            }
        }

        // --- 3) MakerOnline ---
        var ru: Uploader.Resumen? = null
        var sinMakerOnline: List<String> = emptyList()
        val otras = listOfNotNull(Web.CREALITY.takeIf { op.creality }, Web.NEXPRINT.takeIf { op.nexprint })
        if (op.makerOnline) {
            val paraMakerOnline = op.carpetas.filter { n ->
                val dir = pendientes.resolve(n)
                Files.isDirectory(dir) &&
                    (!op.creality || SubidorCreality.yaSubido(dir) || n in sin3mf) &&
                    (!op.nexprint || SubidorNexprint.yaSubido(dir))
            }
            if (otras.isNotEmpty()) {
                sinMakerOnline = op.carpetas.filter { it !in paraMakerOnline && Files.isDirectory(pendientes.resolve(it)) }
                progreso.info("── ${Web.MAKERONLINE.nombre} ──")
                if (sinMakerOnline.isNotEmpty()) {
                    progreso.nota("No van a ${Web.MAKERONLINE.nombre} porque no se han subido a " +
                        otras.joinToString(" y ") { it.nombre } + " (siguen en pendientes): ${sinMakerOnline.joinToString(", ")}")
                }
            }
            if (paraMakerOnline.isNotEmpty()) {
                ru = Uploader(rutas, conWeb(Web.MAKERONLINE), cancelacion, revisionDe(Web.MAKERONLINE))
                    .ejecutar(Uploader.Opciones(op.revisar, paraMakerOnline, false, op.verChrome))
            }
        }

        // --- 4) Sin MakerOnline: las que ya están en todas las webs elegidas han terminado ---
        var aSubidos = 0
        if (!op.makerOnline) {
            try {
                Files.createDirectories(rutas.subidos)
            } catch (e: IOException) {
                progreso.error("No he podido crear ${rutas.subidos}: ${e.message}")
            }
            for (n in op.carpetas) {
                val dir = pendientes.resolve(n)
                if (!Files.isDirectory(dir)) continue
                if (op.creality && !SubidorCreality.yaSubido(dir)) continue
                if (op.nexprint && !SubidorNexprint.yaSubido(dir)) continue
                try {
                    val destino = Uploader.mover(dir, rutas.subidos)
                    aSubidos++
                    progreso.correcto("$n → $destino")
                } catch (e: IOException) {
                    // Ya está en Creality: si se queda en pendientes no pasa nada (no se vuelve a subir allí)
                    progreso.error("$n ya está en ${otras.joinToString(" y ") { it.nombre }}, pero no he podido moverla a subidos/: ${e.message}")
                }
            }
        }
        return Resumen(rc, ru, sinMakerOnline, sin3mf, aSubidos, ru?.parado() == true, rn)
    }

    /** Lo que cuenta cada web, con su nombre en "Modelo 1/3 · flower · Creality Cloud". */
    private fun conWeb(web: Web): Progreso = object : Progreso by progreso {
        override fun modelo(indice: Int, total: Int, titulo: String) =
            progreso.modelo(indice, total, "$titulo · ${web.nombre}")
    }

    /** Al revisar, que se sepa de qué web es el formulario ("flower · Creality Cloud"). */
    private fun revisionDe(web: Web): Revision = Revision { modelo -> revision.confirmar("$modelo · ${web.nombre}") }

    private fun tiene3mf(dir: Path): Boolean = try {
        ModelFolder.modelosDe(dir).any { it.fileName.toString().lowercase(Locale.ROOT).endsWith(".3mf") }
    } catch (_: IOException) {
        false
    }
}
