package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgentLock
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.CanceladoException
import dev.jefe.makeronline.Cancelacion
import dev.jefe.makeronline.DescargadorCreality
import dev.jefe.makeronline.Downloader
import dev.jefe.makeronline.ListaUsuarios
import dev.jefe.makeronline.ListaUsuariosCreality
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.Web
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.coroutines.cancellation.CancellationException

/**
 * La pantalla Descargar entera: de qué webs se descarga, lo común a todas (últimos modelos,
 * ventana de Chrome) y lo de descargar (botones, progreso, resumen y zip). La lista de usuarios y
 * las opciones propias de cada web están en [EstadoDescarga] (MakerOnline) y
 * [EstadoDescargaCreality].
 *
 * Un solo botón Descargar: va por las webs marcadas de una en una, en el orden de la pantalla, y
 * al terminar deja todo en un solo zip en el escritorio. Nunca dos descargas a la vez.
 *
 * Todo se cambia desde el hilo de la ventana: las funciones `suspend` vuelven a él al terminar,
 * y los avisos de core llegan a él a través de [ProgresoApp].
 */
class EstadoDescargas(
    private val rutas: Rutas,
    private val hilo: HiloNavegador,
    private val ocupacion: Ocupacion,
    private val ajustes: Ajustes,
    private val sesion: EstadoSesion,
    val makerOnline: EstadoDescarga,
    val creality: EstadoDescargaCreality,
) {
    // ------------------------------------------------------------ webs

    /** Las webs marcadas para descargar (se recuerdan). Solo se descarga de las que además tienen sesión. */
    var marcadaMakerOnline by mutableStateOf(ajustes.descargarMakerOnline)
        private set
    var marcadaCreality by mutableStateOf(ajustes.descargarCreality)
        private set

    fun marcada(web: Web): Boolean = when (web) {
        Web.MAKERONLINE -> marcadaMakerOnline
        Web.CREALITY -> marcadaCreality
        Web.NEXPRINT -> false // de Nexprint no se descarga (Web.descarga)
    }

    fun marcar(web: Web, si: Boolean) {
        when (web) {
            Web.MAKERONLINE -> {
                marcadaMakerOnline = si
                ajustes.descargarMakerOnline = si
            }
            Web.CREALITY -> {
                marcadaCreality = si
                ajustes.descargarCreality = si
            }
            Web.NEXPRINT -> Unit
        }
    }

    /** Si se puede elegir esa web: hay sesión iniciada en ella (pantalla Sesión). */
    fun disponible(web: Web): Boolean = sesion.iniciada(web)

    /** Si se va a descargar de esa web: está marcada y tiene sesión. */
    fun descarga(web: Web): Boolean = marcada(web) && disponible(web)

    /** Las webs de las que se va a descargar, en el orden de la pantalla. */
    fun websQueDescargan(): List<Web> = Web.entries.filter { it.descarga && descarga(it) }

    val sesionComprobada: Boolean get() = sesion.estado != null
    val comprobandoSesion: Boolean get() = ocupacion.tarea == Textos.TAREA_COMPROBANDO

    /** Por qué no se puede elegir una web (en su ficha): no tiene sesión, se está comprobando o sin comprobar. */
    fun motivoNoDisponible(): String = when {
        sesionComprobada -> Textos.SIN_SESION
        comprobandoSesion -> Textos.COMPROBANDO
        else -> Textos.SIN_COMPROBAR
    }

    /** La pestaña de usuarios que se está viendo. */
    var pestana by mutableStateOf(Web.MAKERONLINE)

    /** Cuántos usuarios hay marcados en una web (en MakerOnline, contando al creador). */
    fun marcadosEn(web: Web): Int = when (web) {
        Web.MAKERONLINE -> makerOnline.marcadosConCreador
        Web.CREALITY -> creality.marcados
        Web.NEXPRINT -> 0
    }

    /** Cuántos usuarios hay en la lista de una web (en MakerOnline, contando al creador). */
    fun usuariosEn(web: Web): Int = when (web) {
        Web.MAKERONLINE -> makerOnline.usuarios.size + 1
        Web.CREALITY -> creality.usuarios.size
        Web.NEXPRINT -> 0
    }

    // ------------------------------------------------------------ opciones comunes

    /** Cuántos de los últimos modelos de cada usuario se miran (texto del campo). Se recuerda. */
    var ultimos by mutableStateOf(ajustes.ultimosModelos.toString())
        private set

    fun cambiarUltimos(texto: String) {
        ultimos = texto
        // Solo se guarda un número válido: a medio escribir (campo vacío) se queda el anterior
        texto.toIntOrNull()?.takeIf { it >= 1 }?.let { ajustes.ultimosModelos = it }
    }

    /** Mirar todos los modelos (más lento; útil la primera vez). */
    var todos by mutableStateOf(false)

    /** Cómo se ve Chrome mientras trabaja (para todas las webs). */
    var modo by mutableStateOf(Downloader.Modo.APARTADA)

    /** Vuelve a coger lo que se recuerda (después de traer los datos de antes). */
    fun cargarAjustes() {
        marcadaMakerOnline = ajustes.descargarMakerOnline
        marcadaCreality = ajustes.descargarCreality
        ultimos = ajustes.ultimosModelos.toString()
        makerOnline.cargarAjustes()
        creality.cargarAjustes()
    }

    /** Vuelve a leer las listas de usuarios de todas las webs. */
    fun cargarListas() {
        makerOnline.cargarLista()
        creality.cargarLista()
    }

    /** Por qué no se puede empezar, o null si todo está bien. */
    fun problemaOpciones(): String? {
        val webs = websQueDescargan()
        if (webs.isEmpty()) return if (sesionComprobada) Textos.ERROR_NINGUNA_WEB_DESCARGA else Textos.ERROR_SESION_SIN_COMPROBAR
        if (!todos && (ultimos.toIntOrNull() ?: 0) < 1) return Textos.ERROR_ULTIMOS
        for (web in webs) {
            val problema = when (web) {
                Web.MAKERONLINE -> makerOnline.problema()
                Web.CREALITY -> creality.problema()
                Web.NEXPRINT -> null
            }
            if (problema != null) {
                pestana = web // para que se vea la lista que tiene el problema
                return Textos.problemaEn(web, problema)
            }
        }
        return null
    }

    // ------------------------------------------------------------ ejecución

    /** Cómo ha ido en una web. [error] es por qué no se ha podido ni empezar o se ha cortado. */
    data class ResultadoWeb(
        val web: Web,
        val descargados: Int = 0,
        /** Ya estaban descargados y solo les faltaba el like. */
        val soloLike: Int = 0,
        val yaTenianLike: Int = 0,
        val errores: Int = 0,
        /** Los que no se bajan (Creality: de pago o sin .3mf). */
        val saltados: Int = 0,
        val carpetasConErrores: List<Path> = emptyList(),
        val error: String? = null,
    )

    var enMarcha by mutableStateOf(false)
        private set
    var simulando by mutableStateOf(false)
        private set
    var parando by mutableStateOf(false)
        private set
    /** La web que se está descargando ahora. */
    var webEnCurso by mutableStateOf<Web?>(null)
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
    /** La entrada de la lista que se está mirando ahora (de [webEnCurso]) y las ya terminadas. */
    var usuarioEnCurso by mutableStateOf<String?>(null)
        private set
    private val hechos = mutableStateListOf<Pair<Web, String>>()
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
    /** Cómo ha ido cada web de la última vez (vacío si no se ha descargado nada todavía). */
    val resultados = mutableStateListOf<ResultadoWeb>()
    var ultimaSimulada by mutableStateOf(false)
        private set
    var ultimaParada by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** true mientras se crea el zip en el escritorio. */
    var empaquetando by mutableStateOf(false)
        private set
    /** El zip creado en esta vez, o null (si no se ha bajado nada no se crea). */
    var zip by mutableStateOf<Path?>(null)
        private set
    var modelosEnZip by mutableStateOf(0)
        private set
    var errorZip by mutableStateOf<String?>(null)
        private set

    val libre: Boolean get() = ocupacion.libre

    /** Donde quedan los errores.txt de cada usuario al terminar. */
    val carpetaErrores: Path get() = rutas.erroresDescargas

    /** Si una entrada de la lista de [web] es la que se está mirando ahora. */
    fun enCurso(web: Web, entrada: String): Boolean {
        val actual = usuarioEnCurso ?: return false
        return web == webEnCurso && mismoUsuario(web, actual, entrada)
    }

    /** Si una entrada de la lista de [web] ya se ha terminado en esta vez. */
    fun hecho(web: Web, entrada: String): Boolean = hechos.any { it.first == web && mismoUsuario(web, it.second, entrada) }

    /** En Creality la entrada puede cambiar de nombre durante la descarga: se compara por número. */
    private fun mismoUsuario(web: Web, a: String, b: String): Boolean = when (web) {
        Web.MAKERONLINE -> a == b
        Web.CREALITY -> a == b || (ListaUsuariosCreality.idDe(a) != null && ListaUsuariosCreality.idDe(a) == ListaUsuariosCreality.idDe(b))
        Web.NEXPRINT -> a == b
    }

    /** Para poder parar. Se pide desde la ventana y lo mira core en el hilo del navegador. */
    @Volatile
    private var cancelacion: Cancelacion? = null

    private val progreso = ProgresoApp(::aplicar)

    /** Ruta de descarga-en-curso tal como sale en los mensajes de core (se quita para que se lean mejor). */
    private val prefijoTrabajo = rutas.descargaEnCurso.toAbsolutePath().toString() + File.separator

    /** Simular (solo lista lo que descargaría) o descargar de verdad, de todas las webs marcadas. */
    suspend fun ejecutar(simular: Boolean) {
        cargarListas() // por si se han tocado los archivos a mano
        val problema = problemaOpciones()
        if (problema != null) {
            error = problema
            return
        }
        if (!ocupacion.empezar(if (simular) Textos.TAREA_SIMULANDO else Textos.TAREA_DESCARGANDO)) return
        reiniciar()
        enMarcha = true
        simulando = simular
        ultimaSimulada = simular
        val c = Cancelacion()
        cancelacion = c
        val n = if (todos) 0 else ultimos.toInt()
        var cerrandoApp = false
        // Cuándo empezó la última descarga de una web, para que la siguiente web respete la pausa
        var inicioAnterior = 0L
        try {
            for (web in websQueDescargan()) {
                if (c.pedida()) break
                webEnCurso = web
                usuarioActual = null
                modeloActual = null
                indiceUsuario = null
                anotar(Evento.Mensaje(TipoMensaje.INFO, Textos.empiezaWeb(web)))
                val r = try {
                    when (web) {
                        Web.MAKERONLINE -> {
                            val op = makerOnline.opciones(n, simular, ESPERA, modo)
                            val res = hilo.ejecutar {
                                // Solo un programa con Chrome a la vez: comparten chrome-profile/ con la terminal
                                AgentLock.acquire(rutas.candado, "JujaMaker (descargando)", false, progreso).use {
                                    Downloader(rutas.paraLaApp(), progreso, c).ejecutar(op)
                                }
                            }
                            // No se sabe cuándo empezó su última descarga: se cuenta desde que ha terminado
                            if (!simular && res.descargados() > 0) inicioAnterior = System.currentTimeMillis()
                            if (res.parado()) c.pedir()
                            ResultadoWeb(
                                web, res.descargados(), res.soloLike(), res.yaTenianLike(), res.errores(),
                                carpetasConErrores = res.carpetasConErrores(),
                            )
                        }
                        Web.CREALITY -> {
                            val op = creality.opciones(n, simular, ESPERA, modo, inicioAnterior)
                            val res = hilo.ejecutar {
                                AgentLock.acquire(rutas.candado, "JujaMaker (descargando de Creality)", false, progreso).use {
                                    DescargadorCreality(rutas.paraLaApp(), progreso, c).ejecutar(op)
                                }
                            }
                            if (res.parado) c.pedir()
                            ResultadoWeb(web, res.descargados, res.soloLike, res.yaTenianLike, res.errores, res.saltados)
                        }
                        Web.NEXPRINT -> ResultadoWeb(web) // no se llega: de Nexprint no se descarga
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CanceladoException) {
                    ResultadoWeb(web) // parado mientras empezaba: no es un error
                } catch (e: AgenteException) {
                    ResultadoWeb(web, error = e.message)
                } catch (e: Exception) {
                    ResultadoWeb(web, error = Textos.errorInesperado(e))
                }
                r.error?.let { anotar(Evento.Mensaje(TipoMensaje.ERROR, Textos.problemaEn(web, it))) }
                resultados.add(r)
                // Al pasar a la siguiente web, el de antes ya está terminado
                usuarioEnCurso?.let { hechos.add(web to it) }
                usuarioEnCurso = null
            }
            ultimaParada = c.pedida()
        } catch (e: CancellationException) {
            cerrandoApp = true
            throw e // la app se está cerrando: no es un error (lo bajado irá en el próximo zip)
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
            webEnCurso = null
            usuarioEnCurso = null
            hechos.clear()
            ocupacion.terminar()
            cargarListas() // el Downloader puede crear usuarios.json; Creality pone nombres
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

    /** Botón "Parar": core para en el siguiente punto seguro, sin dejar nada a medias, y no sigue con otra web. */
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
        resultados.clear()
        ultimaParada = false
        error = null
        usuarioActual = null
        modeloActual = null
        paginas = null
        pausaHasta = null
        captcha = null
        parando = false
        indiceUsuario = null
        totalUsuarios = 0
        webEnCurso = null
        usuarioEnCurso = null
        hechos.clear()
        zip = null
        errorZip = null
    }

    /** Aplica un aviso de core. Siempre en el hilo de la ventana (ver ProgresoApp). */
    private fun aplicar(e: Evento) {
        when (e) {
            is Evento.Mensaje -> anotar(e)
            is Evento.Usuario -> {
                val web = webEnCurso ?: Web.MAKERONLINE
                val nombre = when (web) {
                    Web.MAKERONLINE -> ListaUsuarios.describir(e.nombre).nombre.let {
                        if (e.nombre == Creador.ENTRADA) Textos.esCreador(it) else it
                    }
                    Web.CREALITY -> ListaUsuariosCreality.nombreDe(e.nombre)
                        ?: Textos.crealityUsuarioSinNombre(ListaUsuariosCreality.idDe(e.nombre))
                    Web.NEXPRINT -> e.nombre
                }
                usuarioActual = "${e.indice}/${e.total} · $nombre"
                usuarioEnCurso?.let { if (it != e.nombre) hechos.add(web to it) }
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
        /** Pausa entre descargas: entre 10 y 30 s, como en la terminal. */
        const val ESPERA = 10
        const val MAX_MENSAJES = 2000
    }
}
