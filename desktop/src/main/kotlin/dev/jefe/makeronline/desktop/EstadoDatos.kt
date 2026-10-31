package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.Rutas
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * La parte de la pantalla Sesión que trae los datos de antes (ver [TraerDatos]).
 *
 * @param alTerminar se llama después de traer algo, para que las pantallas vuelvan a leer
 *                   la lista de usuarios, las subidas y la sesión
 */
class EstadoDatos(
    private val rutas: Rutas,
    private val hilo: HiloNavegador,
    private val ocupacion: Ocupacion,
    private val alTerminar: suspend (sesionTraida: Boolean) -> Unit,
) {
    /** La carpeta de antes con datos que se ofrece traer (la raíz del repo al lanzarla desde IntelliJ), o null. */
    var sugerida by mutableStateOf(CarpetaDatos.anteriorSugerida(rutas.datos))
        private set

    /** true mientras se copian los datos. */
    var trayendo by mutableStateOf(false)
        private set

    /** Lo que ha salido bien la última vez ("✔ Traído: …"), o null. */
    var resumen by mutableStateOf<String?>(null)
        private set

    /** Lo que no se ha podido traer, o vacío. */
    var avisos by mutableStateOf<List<String>>(emptyList())
        private set

    /** Un error que ha impedido traer nada, o null. */
    var error by mutableStateOf<String?>(null)
        private set

    val carpeta: Path get() = rutas.datos

    /** Si se puede pulsar "Traer": Chrome no está haciendo otra cosa. */
    val libre: Boolean get() = ocupacion.libre && !trayendo

    suspend fun traer(origen: Path) {
        if (!ocupacion.empezar(Textos.TAREA_TRAYENDO)) return
        trayendo = true
        resumen = null
        avisos = emptyList()
        error = null
        var algo = false
        var sesion = false
        try {
            // En el hilo del navegador: así no se congela la ventana y no coincide con nada que use Chrome
            val r = hilo.ejecutar { TraerDatos.traer(origen, rutas) }
            resumen = if (r.traido.isEmpty()) Textos.TRAER_NADA_NUEVO else Textos.traidoResumen(r.traido)
            avisos = r.avisos
            algo = r.traido.isNotEmpty()
            sesion = r.sesion
            sugerida = null // ya está hecho: no se vuelve a ofrecer
        } catch (e: CancellationException) {
            throw e // la app se está cerrando
        } catch (e: AgenteException) {
            error = e.message // otro programa está usando Chrome con una de las dos carpetas
        } catch (e: IllegalArgumentException) {
            error = e.message
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
        } finally {
            trayendo = false
            ocupacion.terminar()
        }
        if (algo) alTerminar(sesion)
    }
}
