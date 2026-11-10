package dev.jefe.makeronline

import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Sube a MakerWorld (Bambu Lab) las carpetas de subidas/pendientes/, con los mismos archivos y textos
 * que se usan para MakerOnline y Creality Cloud.
 *
 * **ESQUELETO.** De momento solo valida las carpetas y dice cuáles subiría: todavía no abre Chrome ni
 * rellena el formulario. Lo que hay que averiguar de la web antes de programarlo está en
 * docs/contexto-makerworld.md. Se sigue el mismo patrón que SubidorCreality:
 *
 *  - Cada modelo se sube como borrador o privado (lo que permita MakerWorld): lo revisas allí y lo
 *    publicas tú.
 *  - No mueve las carpetas (se quedan en pendientes/ para las otras webs): apunta lo subido en
 *    `subido-makerworld.txt` dentro de la carpeta, y las que lo tienen no se vuelven a subir.
 *  - Si algo falla, deja `error-makerworld.txt` (en qué paso y por qué) y `error-makerworld.png`.
 *  - Los captchas no se saltan: se enseña la ventana para que los resuelva la persona.
 *
 * Programa de terminal (configuración "Subir a MakerWorld (revisar)"):
 *   --revisar   antes de enviar cada modelo, se para para que mires el formulario.
 *   --esperar   si otro programa del agente está en marcha, espera a que termine.
 *   nombre ...  solo esas carpetas de pendientes/ (sin nombres, todas).
 */
class SubidorMakerWorld(
    private val rutas: Rutas,
    private val progreso: Progreso,
    private val cancelacion: Cancelacion,
    private val revision: Revision,
) {

    /** Las mismas opciones que SubidorCreality.Opciones. */
    data class Opciones(
        val revisar: Boolean,
        val carpetas: List<String>,
        val esperar: Boolean,
        val verChrome: Boolean = true,
    )

    /** El mismo resumen que SubidorCreality.Resumen. */
    data class Resumen(val subidos: Int, val yaSubidos: Int, val conError: Int, val sinSubir: Int, val parado: Boolean)

    fun ejecutar(op: Opciones): Resumen {
        val pendientes = rutas.pendientes
        Files.createDirectories(pendientes)
        var carpetas = Files.list(pendientes).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }
        if (op.carpetas.isNotEmpty()) {
            for (n in op.carpetas) if (!Files.isDirectory(pendientes.resolve(n))) progreso.error("No existe en pendientes: $n")
            carpetas = carpetas.filter { it.fileName.toString() in op.carpetas }
        }
        if (carpetas.isEmpty()) {
            progreso.info("No hay nada en ${pendientes.toAbsolutePath()}")
            return Resumen(0, 0, 0, 0, false)
        }

        // 1) Validar antes de abrir el navegador (igual que en Creality)
        val listos = ArrayList<ModelFolder>()
        var yaSubidos = 0
        var sinSubir = 0
        for (dir in carpetas) {
            if (cancelacion.pedida()) return Resumen(0, yaSubidos, 0, sinSubir, true)
            if (yaSubido(dir)) {
                progreso.saltado("${dir.fileName}: ya está en MakerWorld ($REGISTRO), no se vuelve a subir")
                yaSubidos++
                continue
            }
            try {
                val m = ModelFolder.load(dir)
                // TODO(makerworld): confirmar si MakerWorld admite .stl o solo .3mf con perfil de impresión
                if (m.perfil() == null) {
                    progreso.error("${dir.fileName}: de momento a MakerWorld solo se sube con un .3mf")
                    sinSubir++
                    continue
                }
                listos += m
                progreso.correcto("${dir.fileName} validado")
            } catch (e: Exception) {
                progreso.error("${dir.fileName}:\n   ${(e.message ?: e.toString()).replace("\n", "\n   ")}")
                sinSubir++
            }
        }

        // 2) Subir: TODO(makerworld). Ver docs/contexto-makerworld.md
        if (listos.isNotEmpty()) {
            progreso.nota("La subida a MakerWorld aún no está hecha. Se subirían: " +
                listos.joinToString(", ") { it.dir().fileName.toString() })
        }
        return Resumen(0, yaSubidos, 0, sinSubir + listos.size, false)
    }

    companion object {
        const val WEB = "https://makerworld.com"

        /** Dentro de la carpeta del modelo: lo subido a MakerWorld (id, dirección y fecha). */
        const val REGISTRO = "subido-makerworld.txt"
        const val CAPTURA_OK = "resultado-makerworld.png"
        const val CAPTURA_ERROR = "error-makerworld.png"
        /** Dentro de la carpeta del modelo: en qué paso falló y por qué. */
        const val ERROR_TXT = "error-makerworld.txt"

        /** Si esa carpeta ya se subió a MakerWorld. */
        @JvmStatic
        fun yaSubido(carpeta: Path): Boolean = Files.exists(carpeta.resolve(REGISTRO))

        @JvmStatic
        fun main(args: Array<String>) {
            val lista = args.toList()
            val op = Opciones(
                revisar = "--revisar" in lista,
                carpetas = lista.filter { !it.startsWith("--") },
                esperar = "--esperar" in lista,
            )
            val rutas = Rutas.carpetaActual()
            val progreso = ProgresoConsola()
            val cancelacion = Cancelacion()
            try {
                AgentLock.acquire(rutas.candado, "Subir a MakerWorld", op.esperar, progreso).use {
                    EntradaConsola.iniciar(cancelacion, progreso)
                    SubidorMakerWorld(rutas, progreso, cancelacion, RevisionConsola(progreso, cancelacion)).ejecutar(op)
                }
            } catch (e: AgenteException) {
                progreso.error(e.message ?: e.toString())
                exitProcess(e.codigoSalida)
            }
            exitProcess(0)
        }
    }
}
