package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgentLock
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.Cancelacion
import dev.jefe.makeronline.Downloader
import dev.jefe.makeronline.ListaUsuarios
import dev.jefe.makeronline.Rutas
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lo que enseña la pantalla Descargar y lo que hacen sus botones.
 *
 * Todo se cambia desde el hilo de la ventana: las funciones `suspend` vuelven a él al terminar,
 * y los avisos de core llegan a él a través de [ProgresoApp].
 *
 * Se descarga en descarga-en-curso/ y, al terminar, todo va a un zip en el escritorio
 * (ver [ZipDescargas]): así no hay que buscar nada dentro de la carpeta del programa.
 */
class EstadoDescarga(
    private val rutas: Rutas,
    private val hilo: HiloNavegador,
    private val ocupacion: Ocupacion,
    private val ajustes: Ajustes,
) {
    // ------------------------------------------------------------ lista de usuarios (usuarios.json)

    var usuarios by mutableStateOf<List<String>>(emptyList())
        private set
    var errorLista by mutableStateOf<String?>(null)
        private set
    var listaExiste by mutableStateOf(true)
        private set

    /**
     * Los usuarios de la lista que están desmarcados (no se descargan). Se guardan los desmarcados y
     * no los marcados: así, un usuario nuevo sale marcado. Se recuerda (ajustes.properties).
     */
    var desmarcados by mutableStateOf(ajustes.desmarcados)
        private set

    fun marcado(entrada: String): Boolean = entrada !in desmarcados

    /** Cuántos de la lista están marcados (sin contar al creador). */
    val marcados: Int get() = usuarios.count { it !in desmarcados }

    fun cambiarMarca(entrada: String, marcar: Boolean) {
        guardarDesmarcados(if (marcar) desmarcados - entrada else desmarcados + entrada)
    }

    /** Marca a todos, también al creador. */
    fun marcarTodos() {
        guardarDesmarcados(emptySet())
        cambiarCreador(true)
    }

    /** Desmarca a todos, también al creador (para luego marcar solo a uno). */
    fun desmarcarTodos() {
        guardarDesmarcados(usuarios.toSet())
        cambiarCreador(false)
    }

    private fun guardarDesmarcados(nuevos: Set<String>) {
        desmarcados = nuevos
        ajustes.desmarcados = nuevos
    }

    /** Si va el creador de la app el primero (ver [Creador]). Se recuerda. */
    var incluirCreador by mutableStateOf(ajustes.incluirCreador)
        private set

    fun cambiarCreador(incluir: Boolean) {
        incluirCreador = incluir
        ajustes.incluirCreador = incluir
    }

    /** Vuelve a coger lo que se recuerda (después de traer los datos de antes). */
    fun cargarAjustes() {
        incluirCreador = ajustes.incluirCreador
        desmarcados = ajustes.desmarcados
    }

    // ------------------------------------------------------------ opciones (como en la terminal)

    /** Cuántos de los últimos modelos de cada usuario se miran (texto del campo). */
    var ultimos by mutableStateOf("10")
    /** Mirar todos los modelos (más lento; útil la primera vez). */
    var todos by mutableStateOf(false)
    /** Descargar también el perfil de impresión (.3mf). */
    var perfil by mutableStateOf(false)
    /** Máximo de descargas en esta vez (texto del campo; vacío = sin límite). */
    var maximo by mutableStateOf("")
    var modo by mutableStateOf(Downloader.Modo.APARTADA)

    // ------------------------------------------------------------ ejecución

    var enMarcha by mutableStateOf(false)
        private set
    var simulando by mutableStateOf(false)
        private set
    var parando by mutableStateOf(false)
        private set
    /** "2/9 · KLV" */
    var usuarioActual by mutableStateOf<String?>(null)
        private set
    /** "3/10 · Llavero gato" */
    var modeloActual by mutableStateOf<String?>(null)
        private set
    /** El número del usuario que se está mirando (4 de "4/9") y cuántos hay; para la barra de progreso. */
    var indiceUsuario by mutableStateOf<Int?>(null)
        private set
    var totalUsuarios by mutableStateOf(0)
        private set
    /** La entrada de la lista (o el creador) que se está mirando ahora, y las ya terminadas. */
    var usuarioEnCurso by mutableStateOf<String?>(null)
        private set
    val usuariosHechos = mutableStateListOf<String>()
    /** Cuánto dura la pausa en curso (milisegundos), para la barrita de la cuenta atrás. */
    var pausaTotal by mutableStateOf(0L)
        private set
    /** "2/7" mientras lee la lista de modelos de un usuario con "todos". */
    var paginas by mutableStateOf<String?>(null)
        private set
    /** Hasta cuándo dura la pausa entre descargas (milisegundos del reloj), o null. */
    var pausaHasta by mutableStateOf<Long?>(null)
        private set
    /** Texto del aviso de captcha, o null si no hay. */
    var captcha by mutableStateOf<String?>(null)
        private set
    val mensajes = mutableStateListOf<Evento.Mensaje>()
    var resumen by mutableStateOf<Downloader.Resumen?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** true mientras se crea el zip en el escritorio. */
    var empaquetando by mutableStateOf(false)
        private set
    /** El zip creado en esta vez, o null (si no se ha bajado nada no se crea). */
    var zip by mutableStateOf<Path?>(null)
        private set
    /** Cuántos modelos lleva [zip]. */
    var modelosEnZip by mutableStateOf(0)
        private set
    /** Por qué no se ha podido crear el zip, o null. */
    var errorZip by mutableStateOf<String?>(null)
        private set

    val libre: Boolean get() = ocupacion.libre

    /** Para poder parar. Se pide desde la ventana y lo mira core en el hilo del navegador. */
    @Volatile
    private var cancelacion: Cancelacion? = null

    private val progreso = ProgresoApp(::aplicar)

    /** Ruta de descarga-en-curso tal como sale en los mensajes de core (se quita para que se lean mejor). */
    private val prefijoTrabajo = rutas.descargaEnCurso.toAbsolutePath().toString() + File.separator

    /** Vuelve a leer usuarios.json. */
    fun cargarLista() {
        val l = ListaUsuarios.leer(rutas.usuarios)
        usuarios = l.entradas
        errorLista = l.error
        listaExiste = l.existe
        // Los desmarcados que ya no están en la lista (quitados con Editar) se olvidan.
        // Con usuarios.json roto no se toca nada: se perderían.
        if (l.error == null && l.existe && desmarcados.any { it !in usuarios }) {
            guardarDesmarcados(desmarcados.filter { it in usuarios }.toSet())
        }
    }

    /** Por qué no se puede empezar con estas opciones, o null si están bien. */
    fun problemaOpciones(): String? {
        if (!todos && (ultimos.toIntOrNull() ?: 0) < 1) return Textos.ERROR_ULTIMOS
        if (maximo.isNotBlank() && (maximo.toIntOrNull() ?: 0) < 1) return Textos.ERROR_MAXIMO
        if (errorLista != null) return Textos.ERROR_LISTA_ROTA
        // Si usuarios.json no existe se deja seguir: el Downloader lo crea con instrucciones
        if (listaExiste && marcados == 0 && !incluirCreador) {
            return if (usuarios.isEmpty()) Textos.ERROR_LISTA_VACIA else Textos.ERROR_NINGUNO_MARCADO
        }
        return null
    }

    /** Simular (solo lista lo que descargaría) o descargar de verdad. */
    suspend fun ejecutar(simular: Boolean) {
        val problema = problemaOpciones()
        if (problema != null) {
            error = problema
            return
        }
        if (!ocupacion.empezar(if (simular) Textos.TAREA_SIMULANDO else Textos.TAREA_DESCARGANDO)) return
        reiniciar()
        enMarcha = true
        simulando = simular
        val c = Cancelacion()
        cancelacion = c
        val opciones = Downloader.Opciones(
            null,
            if (todos) 0 else ultimos.toInt(),
            simular,
            maximo.toIntOrNull() ?: Int.MAX_VALUE,
            ESPERA,
            perfil,
            modo,
            false,
            // El creador va el primero (si está marcado); los desmarcados de la lista, no
            if (incluirCreador) listOf(Creador.ENTRADA) else emptyList(),
            desmarcados.toList(),
        )
        var cerrandoApp = false
        try {
            resumen = hilo.ejecutar {
                // Solo un programa con Chrome a la vez: comparten chrome-profile/ con la terminal
                AgentLock.acquire(rutas.candado, "JujaMaker (descargando)", false, progreso).use {
                    Downloader(rutas.paraLaApp(), progreso, c).ejecutar(opciones)
                }
            }
        } catch (e: CancellationException) {
            cerrandoApp = true
            throw e // la app se está cerrando: no es un error (lo bajado irá en el próximo zip)
        } catch (e: AgenteException) {
            error = e.message
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
        } finally {
            // También si se ha parado o ha fallado: lo que esté completo va al zip
            if (!simular && !cerrandoApp) empaquetar()
            enMarcha = false
            parando = false
            cancelacion = null
            pausaHasta = null
            captcha = null
            paginas = null
            indiceUsuario = null
            usuarioEnCurso = null
            usuariosHechos.clear()
            ocupacion.terminar()
            cargarLista() // por si el Downloader ha creado usuarios.json
        }
    }

    /** Pasa lo descargado a un zip en el escritorio (en el hilo del navegador, para no congelar la ventana). */
    private suspend fun empaquetar() {
        if (!Files.isDirectory(rutas.descargaEnCurso)) return
        empaquetando = true
        usuarioActual = null
        modeloActual = null
        pausaHasta = null
        try {
            val r = hilo.ejecutar {
                ZipDescargas.empaquetar(rutas.descargaEnCurso, ZipDescargas.escritorio(), rutas.erroresDescargas)
            }
            r.zip?.let {
                zip = it
                modelosEnZip = r.modelos
                anotar(Evento.Mensaje(TipoMensaje.CORRECTO, Textos.zipCreado(it.fileName.toString(), r.modelos)))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorZip = Textos.errorZip(e.message ?: e.toString())
        } finally {
            empaquetando = false
        }
    }

    /** Dónde está el errores.txt de una carpeta de usuario del resumen (se guarda aparte al terminar). */
    fun erroresDe(carpetaUsuario: Path): Path {
        val guardado = rutas.erroresDescargas.resolve(carpetaUsuario.fileName.toString()).resolve("errores.txt")
        return if (Files.exists(guardado)) guardado else carpetaUsuario.resolve("errores.txt")
    }

    /** Botón "Parar": core para en el siguiente punto seguro, sin dejar nada a medias. */
    fun parar() {
        cancelacion?.pedir()
        parando = true
    }

    /** Al cerrar la app: si estaba descargando, que pare. */
    fun alCerrarApp() {
        cancelacion?.pedir()
    }

    private fun reiniciar() {
        mensajes.clear()
        resumen = null
        error = null
        usuarioActual = null
        modeloActual = null
        paginas = null
        pausaHasta = null
        captcha = null
        parando = false
        indiceUsuario = null
        totalUsuarios = 0
        usuarioEnCurso = null
        usuariosHechos.clear()
        zip = null
        errorZip = null
    }

    /** Aplica un aviso de core. Siempre en el hilo de la ventana (ver ProgresoApp). */
    private fun aplicar(e: Evento) {
        when (e) {
            is Evento.Mensaje -> anotar(e)
            is Evento.Usuario -> {
                val nombre = ListaUsuarios.describir(e.nombre).nombre
                usuarioActual = "${e.indice}/${e.total} · " +
                    if (e.nombre == Creador.ENTRADA) Textos.esCreador(nombre) else nombre
                usuarioEnCurso?.let { if (it != e.nombre && it !in usuariosHechos) usuariosHechos.add(it) }
                usuarioEnCurso = e.nombre
                indiceUsuario = e.indice
                totalUsuarios = e.total
                modeloActual = null
                paginas = null
            }
            is Evento.Modelo -> {
                modeloActual = "${e.indice}/${e.total} · ${e.titulo}"
                pausaHasta = null
                paginas = null
            }
            is Evento.Paginas -> paginas = "${e.pagina}/${e.ultima}"
            is Evento.Pausa -> {
                pausaTotal = e.segundos * 1000L
                pausaHasta = System.currentTimeMillis() + pausaTotal
                anotar(Evento.Mensaje(TipoMensaje.INFO, Textos.pausa(e.segundos)))
            }
            is Evento.Captcha -> {
                captcha = Textos.captcha(e.para, e.minutos)
                anotar(Evento.Mensaje(TipoMensaje.AVISO, captcha!!))
            }
            Evento.CaptchaTerminado -> captcha = null
        }
    }

    private fun anotar(original: Evento.Mensaje) {
        // core cuenta dónde guarda cada cosa; en la app eso es una carpeta temporal que acaba en el
        // zip, así que se deja solo la parte "usuario/modelo" y se quita la línea "Carpeta: …"
        if (original.texto.trim() == "Carpeta: " + prefijoTrabajo.dropLast(1)) return
        val m = if (prefijoTrabajo !in original.texto) original else original.copy(
            texto = original.texto
                .replace(Regex(" descargados en " + Regex.escape(prefijoTrabajo) + "[^\\\\/]+$"), " descargados")
                .replace(prefijoTrabajo, ""),
        )
        mensajes.add(m)
        if (mensajes.size > MAX_MENSAJES) mensajes.removeAt(0)
    }

    private companion object {
        /** Pausa entre descargas: entre 10 y 30 s, como en la terminal (se podrá cambiar en Ajustes). */
        const val ESPERA = 10
        const val MAX_MENSAJES = 2000
    }
}
