package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.Sesion
import dev.jefe.makeronline.Web
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lo que enseña la pantalla Sesión y lo que hacen sus botones (MakerOnline, Creality Cloud y MakerWorld).
 *
 * Las funciones `suspend` se llaman desde la ventana: el trabajo con Chrome va al hilo del
 * navegador y, al volver, el estado se cambia ya en el hilo de la ventana.
 */
class EstadoSesion(
    rutas: Rutas,
    private val hilo: HiloNavegador,
    private val ocupacion: Ocupacion,
) {
    private val sesion = Sesion(rutas)

    /** null = todavía sin comprobar. */
    var estado by mutableStateOf<Sesion.Estado?>(null)
        private set

    /** A qué hora se comprobó la sesión por última vez ("22:05"), o null si todavía no. */
    var comprobadaA by mutableStateOf<String?>(null)
        private set

    /** true mientras esta pantalla tiene a Chrome trabajando (para la barra de progreso). */
    var ocupado by mutableStateOf(false)
        private set

    /** Un error para enseñar en rojo, o null. */
    var error by mutableStateOf<String?>(null)
        private set

    /** true mientras Chrome está abierto esperando a que la persona inicie sesión. */
    var esperandoLogin by mutableStateOf(false)
        private set

    /** En qué web se está iniciando sesión ahora (con Chrome abierto), o null. */
    var webEsperando by mutableStateOf<Web?>(null)
        private set

    /** La web del último error al abrir Chrome para iniciar sesión, o null si el error es general. */
    var errorDe by mutableStateOf<Web?>(null)
        private set

    /** Si hay sesión de MakerOnline (comprobada con MakerOnline). */
    val makerOnline: Boolean get() = estado?.iniciada == true

    /** Si hay sesión de Creality Cloud guardada en Chrome. */
    val creality: Boolean get() = estado?.creality == true

    /** Si MakerWorld acepta la sesión guardada en Chrome. */
    val makerWorld: Boolean get() = estado?.makerWorld == true

    /** Si hay sesión en esa web. */
    fun iniciada(web: Web): Boolean = when (web) {
        Web.MAKERONLINE -> makerOnline
        Web.CREALITY -> creality
        Web.MAKERWORLD -> makerWorld
    }

    /** El nombre de la cuenta en esa web, si se sabe (de Creality y MakerWorld solo se mira que haya sesión). */
    fun cuenta(web: Web): String? = when (web) {
        Web.MAKERONLINE -> estado?.nombre
        Web.CREALITY -> null
        Web.MAKERWORLD -> null
    }

    /** Si se pueden pulsar los botones: Chrome no está haciendo otra cosa (por ejemplo, descargar). */
    val libre: Boolean get() = ocupacion.libre

    /** Chrome abierto para iniciar sesión. Solo se toca desde el hilo del navegador. */
    @Volatile
    private var abierta: Sesion.Abierta? = null

    suspend fun comprobar() {
        if (!ocupacion.empezar(Textos.TAREA_COMPROBANDO)) return
        ocupado = true
        error = null
        errorDe = null
        try {
            estado = hilo.ejecutar { sesion.comprobar(ProgresoNulo) }
            comprobadaA = LocalTime.now().format(HORA)
        } catch (e: CancellationException) {
            throw e // la app se está cerrando: no es un error
        } catch (e: Exception) {
            error = mensajeDe(e)
        } finally {
            ocupado = false
            ocupacion.terminar()
        }
    }

    /** Abre Chrome en [web] para que la persona inicie sesión allí. */
    suspend fun abrirParaIniciar(web: Web = Web.MAKERONLINE) {
        if (!ocupacion.empezar(Textos.TAREA_INICIANDO_SESION)) return
        ocupado = true
        error = null
        errorDe = null
        webEsperando = web // ya desde ahora, para que la barra de progreso salga solo en su tarjeta
        try {
            hilo.ejecutar { abierta = sesion.abrirParaIniciar(ProgresoNulo, web) }
            esperandoLogin = true // Chrome sigue ocupado hasta que se cierre (ver cerrarChrome)
        } catch (e: CancellationException) {
            webEsperando = null
            throw e // la app se está cerrando: no es un error
        } catch (e: Exception) {
            error = mensajeDe(e)
            errorDe = web
            webEsperando = null
            ocupacion.terminar()
        } finally {
            ocupado = false
        }
    }

    /** La persona dice que ya ha entrado: se cierra Chrome (así se guarda la sesión) y se comprueba. */
    suspend fun yaHeEntrado() {
        cerrarChrome()
        comprobar()
    }

    suspend fun cancelarInicio() {
        cerrarChrome()
        comprobar()
    }

    fun alCerrarApp() {
        hilo.alCerrar { cerrarAbierta() }
    }

    private suspend fun cerrarChrome() {
        ocupado = true
        try {
            hilo.ejecutar { cerrarAbierta() }
        } catch (_: Exception) {
            // si ya estaba cerrado, no pasa nada
        } finally {
            ocupado = false
            esperandoLogin = false
            webEsperando = null
            ocupacion.terminar()
        }
    }

    private fun cerrarAbierta() {
        abierta?.close()
        abierta = null
    }

    private fun mensajeDe(e: Exception): String? =
        if (e is AgenteException) e.message else Textos.errorInesperado(e) // p. ej. otro programa usa Chrome

    private companion object {
        val HORA: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
