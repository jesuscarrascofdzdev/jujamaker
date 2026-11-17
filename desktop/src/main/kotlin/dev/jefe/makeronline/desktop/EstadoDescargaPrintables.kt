package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.DescargasPrintables
import dev.jefe.makeronline.ListaUsuariosPrintables
import dev.jefe.makeronline.Rutas
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/**
 * La parte de Printables de la pantalla Descargar: su lista de usuarios (usuarios-printables.json),
 * a quién se marca, su máximo y la descarga.
 *
 * A diferencia de MakerOnline y Creality, no descarga la app sino la extensión de Chrome (Cloudflare no
 * deja entrar al Chrome de la app; ver DescargasPrintables en core). Por eso va aparte del botón común
 * de Descargar: "Descargar de Printables" prepara el trabajo y abre Printables en el Chrome de siempre;
 * la extensión lo coge, descarga y le va pasando los archivos a la app. Al terminar (o al parar), lo
 * completo va al zip del escritorio, como lo demás. Mientras tanto la app está ocupada (descarga-en-curso
 * es la misma carpeta para todas las webs, así que nunca dos descargas a la vez).
 *
 * Todo se cambia desde el hilo de la ventana: lo que llega de la extensión pasa por [ProgresoApp].
 */
class EstadoDescargaPrintables(
    private val rutas: Rutas,
    private val ajustes: Ajustes,
    private val ocupacion: Ocupacion,
    private val printables: EstadoPrintables,
) {
    private val descargas: DescargasPrintables get() = printables.descargas

    /** Si en Descargar se está viendo la pestaña Printables (en vez de la de una web de [dev.jefe.makeronline.Web]). */
    var pestanaActiva by mutableStateOf(false)

    // ------------------------------------------------------------ lista de usuarios (usuarios-printables.json)

    var usuarios by mutableStateOf<List<String>>(emptyList())
        private set
    var errorLista by mutableStateOf<String?>(null)
        private set

    /** Los usuarios desmarcados (no se descargan), por su handle en minúsculas. Se recuerda. */
    var desmarcados by mutableStateOf(ajustes.desmarcadosPrintables)
        private set

    fun marcado(entrada: String): Boolean = ListaUsuariosPrintables.claveDe(entrada) !in desmarcados

    val marcados: Int get() = usuarios.count { marcado(it) }

    fun cambiarMarca(entrada: String, marcar: Boolean) {
        val clave = ListaUsuariosPrintables.claveDe(entrada) ?: return
        guardarDesmarcados(if (marcar) desmarcados - clave else desmarcados + clave)
    }

    fun marcarTodos() = guardarDesmarcados(emptySet())

    fun desmarcarTodos() = guardarDesmarcados(usuarios.mapNotNull { ListaUsuariosPrintables.claveDe(it) }.toSet())

    private fun guardarDesmarcados(nuevos: Set<String>) {
        desmarcados = nuevos
        ajustes.desmarcadosPrintables = nuevos
    }

    /** Lo que se está escribiendo en el campo de añadir. */
    var texto by mutableStateOf("")
    /** Por qué no se ha podido añadir o quitar, o null. */
    var errorEdicion by mutableStateOf<String?>(null)
        private set
    /** La entrada que se va a quitar (se pregunta antes), o null. */
    var aQuitar by mutableStateOf<String?>(null)

    /** Vuelve a leer usuarios-printables.json. */
    fun cargarLista() {
        val l = ListaUsuariosPrintables.leer(rutas.usuariosPrintables)
        usuarios = l.entradas
        errorLista = l.error
    }

    /** Añade el usuario escrito en el campo (la dirección de su perfil o su @handle). */
    fun agregar() {
        errorEdicion = null
        try {
            ListaUsuariosPrintables.agregar(rutas.usuariosPrintables, texto.trim())
            texto = ""
        } catch (e: AgenteException) {
            errorEdicion = e.message
        } catch (e: Exception) {
            errorEdicion = Textos.errorInesperado(e)
        }
        cargarLista()
    }

    fun quitar(entrada: String) {
        errorEdicion = null
        aQuitar = null
        try {
            ListaUsuariosPrintables.quitar(rutas.usuariosPrintables, entrada)
            ListaUsuariosPrintables.claveDe(entrada)?.let { guardarDesmarcados(desmarcados - it) }
        } catch (e: AgenteException) {
            errorEdicion = e.message
        } catch (e: Exception) {
            errorEdicion = Textos.errorInesperado(e)
        }
        cargarLista()
    }

    // ------------------------------------------------------------ opciones propias de Printables

    /** Máximo de descargas de Printables de cada vez (texto del campo; vacío = sin límite). Se recuerda. */
    var maximo by mutableStateOf(ajustes.maximoPrintables?.toString() ?: "")
        private set

    fun cambiarMaximo(texto: String) {
        maximo = texto
        when {
            texto.isBlank() -> ajustes.maximoPrintables = null
            (texto.toIntOrNull() ?: 0) >= 1 -> ajustes.maximoPrintables = texto.toInt()
        }
    }

    /** Vuelve a coger lo que se recuerda (después de traer los datos de antes). */
    fun cargarAjustes() {
        desmarcados = ajustes.desmarcadosPrintables
        maximo = ajustes.maximoPrintables?.toString() ?: ""
    }

    /** Por qué no se puede descargar de Printables con esto, o null si está bien. */
    fun problema(): String? {
        if (printables.error != null) return printables.error
        if (maximo.isNotBlank() && (maximo.toIntOrNull() ?: 0) < 1) return Textos.PRD_ERROR_MAXIMO
        if (errorLista != null) return Textos.PRD_ERROR_LISTA_ROTA
        if (usuarios.isEmpty()) return Textos.PRD_ERROR_LISTA_VACIA
        if (marcados == 0) return Textos.PRD_ERROR_NINGUNO_MARCADO
        return null
    }

    // ------------------------------------------------------------ la descarga (la hace la extensión)

    var enMarcha by mutableStateOf(false)
        private set
    var simulando by mutableStateOf(false)
        private set
    var parando by mutableStateOf(false)
        private set
    /** "2/9 · VC Design" */
    var usuarioActual by mutableStateOf<String?>(null)
        private set
    /** "3/10 · Drill Paint Mixer" */
    var modeloActual by mutableStateOf<String?>(null)
        private set
    /** Hasta cuándo dura la pausa entre descargas (milisegundos del reloj), o null. */
    var pausaHasta by mutableStateOf<Long?>(null)
        private set
    val mensajes = mutableStateListOf<Evento.Mensaje>()
    var resultado by mutableStateOf<DescargasPrintables.Resumen?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var empaquetando by mutableStateOf(false)
        private set
    var zip by mutableStateOf<Path?>(null)
        private set
    var modelosEnZip by mutableStateOf(0)
        private set
    var errorZip by mutableStateOf<String?>(null)
        private set

    /** Si la extensión ya ha cogido el trabajo (si no, puede que no esté instalada o al día). */
    val conectada: Boolean get() = descargas.conectada

    val libre: Boolean get() = ocupacion.libre

    private val progreso = ProgresoApp(::aplicar)

    /**
     * "Descargar de Printables" (o "Simular"): prepara el trabajo y abre Printables en el Chrome de
     * siempre. [ultimos] es el de las opciones comunes (0 = todos).
     */
    fun empezar(simular: Boolean, ultimos: Int) {
        cargarLista()
        error = problema()
        if (error != null) return
        if (!ocupacion.empezar(if (simular) Textos.TAREA_SIMULANDO else Textos.PRD_TAREA)) return
        mensajes.clear()
        resultado = null
        zip = null
        errorZip = null
        usuarioActual = null
        modeloActual = null
        pausaHasta = null
        parando = false
        val op = DescargasPrintables.Opciones(
            ultimos = ultimos,
            max = maximo.toIntOrNull() ?: Int.MAX_VALUE,
            simular = simular,
            saltar = usuarios.filterNot { marcado(it) },
        )
        try {
            descargas.empezar(op, progreso) { r -> SwingUtilities.invokeLater { terminado(r) } }
        } catch (e: AgenteException) {
            error = e.message
            ocupacion.terminar()
            return
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
            ocupacion.terminar()
            return
        }
        enMarcha = true
        simulando = simular
        anotar(Evento.Mensaje(TipoMensaje.INFO, Textos.PRD_ABIERTO))
        abrirEnNavegador(DescargasPrintables.DIRECCION)
    }

    /** Vuelve a abrir Printables (si se ha cerrado la pestaña o no ha arrancado). */
    fun reabrir() = abrirEnNavegador(DescargasPrintables.DIRECCION)

    /**
     * "Parar": si la extensión está descargando, para en el siguiente punto seguro y avisa; si todavía
     * no ha cogido el trabajo, se da por terminado aquí.
     */
    fun parar() {
        parando = true
        if (descargas.conectada) descargas.parar() else descargas.cancelar()
    }

    /** Al cerrar la app: no se hace el zip ahora; lo que esté completo irá en el de la próxima vez. */
    fun alCerrarApp() {
        cerrandoApp = true
        if (enMarcha) descargas.cancelar()
    }

    @Volatile
    private var cerrandoApp = false

    /** La extensión ha terminado (o se ha parado): lo completo va al zip del escritorio. */
    private fun terminado(r: DescargasPrintables.Resumen) {
        resultado = r
        usuarioActual = null
        modeloActual = null
        pausaHasta = null
        if (r.simulado || cerrandoApp || !Files.isDirectory(rutas.descargaEnCurso)) {
            fin()
            return
        }
        empaquetando = true
        // Fuera del hilo de la ventana, para no congelarla mientras se hace el zip
        thread(name = "zip-printables", isDaemon = true) {
            val salida: Result<ZipDescargas.Resultado> = try {
                Result.success(ZipDescargas.empaquetar(rutas.descargaEnCurso, ZipDescargas.escritorio(), rutas.erroresDescargas))
            } catch (e: Exception) {
                Result.failure(e)
            }
            SwingUtilities.invokeLater {
                salida.onSuccess { z ->
                    z.zip?.let {
                        zip = it
                        modelosEnZip = z.modelos
                        anotar(Evento.Mensaje(TipoMensaje.CORRECTO, Textos.zipCreado(it.fileName.toString(), z.modelos)))
                    }
                }.onFailure { errorZip = Textos.errorZip(it.message ?: it.toString()) }
                empaquetando = false
                fin()
            }
        }
    }

    private fun fin() {
        enMarcha = false
        parando = false
        ocupacion.terminar()
        cargarLista() // la extensión pone los nombres de los usuarios
    }

    /** Donde quedan los errores.txt de cada usuario al terminar. */
    val carpetaErrores: Path get() = rutas.erroresDescargas

    private fun aplicar(e: Evento) {
        when (e) {
            is Evento.Mensaje -> anotar(e)
            is Evento.Usuario -> {
                val nombre = ListaUsuariosPrintables.nombreDe(e.nombre)
                    ?: ListaUsuariosPrintables.handleDe(e.nombre)?.let { "@$it" } ?: e.nombre
                usuarioActual = "${e.indice}/${e.total} · $nombre"
                modeloActual = null
            }
            is Evento.Modelo -> {
                modeloActual = "${e.indice}/${e.total} · ${e.titulo}"
                pausaHasta = null
            }
            is Evento.Pausa -> {
                pausaHasta = System.currentTimeMillis() + e.segundos * 1000L
                anotar(Evento.Mensaje(TipoMensaje.INFO, Textos.pausa(e.segundos)))
            }
            is Evento.Paginas, is Evento.Captcha, Evento.CaptchaTerminado -> Unit
        }
    }

    private fun anotar(m: Evento.Mensaje) {
        mensajes.add(m)
        if (mensajes.size > MAX_MENSAJES) mensajes.removeAt(0)
    }

    private companion object {
        const val MAX_MENSAJES = 1000
    }
}
