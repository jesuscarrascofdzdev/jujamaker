package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.ListaUsuarios
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.Sesion
import kotlin.coroutines.cancellation.CancellationException

/**
 * La ventana "Editar la lista de usuarios": añadir (comprobando en MakerOnline que existe) y quitar.
 *
 * La lista que se enseña es la de la pantalla Descargar ([EstadoDescarga.usuarios]); después de
 * cada cambio se guarda usuarios.json y se vuelve a leer. Todo se cambia desde el hilo de la ventana.
 */
class EstadoEdicion(
    private val rutas: Rutas,
    private val hilo: HiloNavegador,
    private val ocupacion: Ocupacion,
    private val descarga: EstadoDescarga,
) {
    private val sesion = Sesion(rutas)

    /** Si la ventana está abierta. */
    var abierta by mutableStateOf(false)
        private set

    /** Lo escrito en el campo de añadir. */
    var texto by mutableStateOf("")

    /** true mientras Chrome busca al usuario en MakerOnline. */
    var buscando by mutableStateOf(false)
        private set

    /** Lo último que ha salido bien ("✓ Añadido: …"), o null. */
    var aviso by mutableStateOf<String?>(null)
        private set

    /** Lo último que ha fallado, para enseñarlo en rojo, o null. */
    var error by mutableStateOf<String?>(null)
        private set

    /** La entrada que se ha pedido quitar y espera el "Sí, quitar", o null. */
    var aQuitar by mutableStateOf<String?>(null)

    val usuarios: List<String> get() = descarga.usuarios

    /** Si Chrome está libre para buscar (no está descargando, comprobando la sesión…). */
    val libre: Boolean get() = ocupacion.libre

    /** Lo que está haciendo Chrome, o null. */
    val tarea: String? get() = ocupacion.tarea

    fun abrir() {
        descarga.cargarLista()
        texto = ""
        aviso = null
        error = null
        aQuitar = null
        abierta = true
    }

    /** Si se cierra mientras busca, la búsqueda termina igual y el usuario se añade. */
    fun cerrar() {
        abierta = false
    }

    /** Busca en MakerOnline lo escrito y, si existe y no está ya, lo añade como "Nombre · URL". */
    suspend fun agregar() {
        val entrada = texto.trim()
        if (entrada.isEmpty() || buscando) return
        aviso = null
        error = null
        aQuitar = null
        if (!ocupacion.empezar(Textos.TAREA_BUSCANDO_USUARIO)) {
            error = Textos.chromeOcupado(ocupacion.tarea ?: "")
            return
        }
        buscando = true
        try {
            val b = hilo.ejecutar { sesion.buscarUsuario(entrada, ProgresoNulo) }
            val u = b.usuario
            if (u == null) {
                error = b.error
                return
            }
            // Se compara con el archivo tal como está ahora (por si se ha tocado a mano)
            val actuales = ListaUsuarios.leer(rutas.usuarios).entradas
            val repetida = ListaUsuarios.repetida(actuales, u.id(), u.handle(), u.nombre())
            if (repetida != null) {
                error = Textos.yaEsta(u.nombre(), ListaUsuarios.describir(repetida).nombre)
                return
            }
            ListaUsuarios.agregar(rutas.usuarios, "${u.nombre()} · ${u.url()}")
            aviso = Textos.agregado(u.nombre(), u.handle())
            texto = ""
        } catch (e: CancellationException) {
            throw e // la app se está cerrando: no es un error
        } catch (e: AgenteException) {
            error = e.message
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
        } finally {
            buscando = false
            ocupacion.terminar()
            descarga.cargarLista()
        }
    }

    /** Quita una entrada (después de que la persona diga "Sí, quitar"). */
    fun quitar(entrada: String) {
        aviso = null
        error = null
        aQuitar = null
        try {
            ListaUsuarios.quitar(rutas.usuarios, entrada)
            aviso = Textos.quitado(ListaUsuarios.describir(entrada).nombre)
        } catch (e: AgenteException) {
            error = e.message
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
        } finally {
            descarga.cargarLista()
        }
    }
}
