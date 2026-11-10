package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgentLock
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.Cancelacion
import dev.jefe.makeronline.InstruccionesIA
import dev.jefe.makeronline.ModelFolder
import dev.jefe.makeronline.ModelMeta
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.SubidaMultiple
import dev.jefe.makeronline.SubidorCreality
import dev.jefe.makeronline.TextosCarpeta
import dev.jefe.makeronline.Web
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Una carpeta de subidas/pendientes/ y si se puede subir.
 *
 * @property problemas lo que hay que arreglar (vacía si está bien)
 * @property avisos    lo que se puede subir pero conviene saber (p. ej. imágenes que de momento no se suben)
 * @property detalle   título y categoría ("Llavero gato · Toys&Games - Action Figures"), si está bien
 */
data class CarpetaPendiente(
    val nombre: String,
    val dir: Path,
    val problemas: List<String>,
    val avisos: List<String> = emptyList(),
    val detalle: String? = null,
    /** La imagen de portada (aunque a la carpeta le falten cosas), para la miniatura; null si no hay. */
    val portada: Path? = null,
    /** Si ya se ha subido a Creality Cloud (tiene subido-creality.txt): no se vuelve a subir allí. */
    val enCreality: Boolean = false,
    /** El error-creality.txt del último intento fallido de subirla a Creality, o null si no hay. */
    val errorCreality: Path? = null,
    /** Si tiene un .3mf (a Creality solo se sube con .3mf). */
    val con3mf: Boolean = false,
) {
    val valida: Boolean get() = problemas.isEmpty()
}

/** Cómo está una carpeta en una web, para las insignias de la lista de pendientes. */
enum class EstadoEnWeb {
    /** Ya está allí. */
    SUBIDO,
    /** Falló la última vez (se puede ver el error). */
    ERROR,
    /** Se subirá allí al pulsar Subir. */
    FALTA,
    /** MakerOnline: se subirá cuando ya esté en Creality. */
    ESPERA,
    /** Creality: no va porque solo tiene .stl. */
    SIN_3MF,
}

/** Una insignia de una carpeta: la web, cómo está allí y, si falló, el archivo con el error. */
data class Insignia(val web: Web, val estado: EstadoEnWeb, val archivo: Path? = null)

/**
 * Lo que enseña la pantalla Subir y lo que hacen sus botones.
 *
 * Se sube a las webs marcadas que tienen sesión (ver [sube]): MakerOnline como borrador y Creality
 * Cloud como privado (ver SubidaMultiple en core).
 *
 * Todo se cambia desde el hilo de la ventana: las funciones `suspend` vuelven a él al terminar,
 * y los avisos de core llegan a él a través de [ProgresoApp].
 */
class EstadoSubida(
    private val rutas: Rutas,
    private val hilo: HiloNavegador,
    private val ocupacion: Ocupacion,
    private val ajustes: Ajustes,
    /** Para saber en qué webs hay sesión. */
    private val sesion: EstadoSesion,
) {
    // ------------------------------------------------------------ carpetas de pendientes/

    var carpetas by mutableStateOf<List<CarpetaPendiente>>(emptyList())
        private set

    /** La carpeta elegida en la lista (para "Solo la seleccionada"), o null. */
    var seleccionada by mutableStateOf<String?>(null)
    var soloSeleccionada by mutableStateOf(false)

    /** true mientras se copian carpetas arrastradas a pendientes/. */
    var copiando by mutableStateOf(false)
        private set
    /** Lo que se ha añadido a pendientes/, o null. */
    var avisoCarpetas by mutableStateOf<String?>(null)
        private set
    /** Lo que no se ha podido añadir y por qué, o null. */
    var errorCarpetas by mutableStateOf<String?>(null)
        private set

    // ------------------------------------------------------------ opciones

    /** Parar antes de guardar cada borrador para mirarlo en Chrome (el --revisar de la terminal). */
    var revisar by mutableStateOf(false)

    /** Ventana de Chrome a la vista mientras sube; si no, trabaja fuera de la pantalla. Se recuerda. */
    var verChrome by mutableStateOf(ajustes.verChromeAlSubir)
        private set

    fun cambiarVerChrome(ver: Boolean) {
        verChrome = ver
        ajustes.verChromeAlSubir = ver
    }

    /** Las webs marcadas para subir (se recuerdan). Solo se sube a las que además tienen sesión. */
    var marcadaMakerOnline by mutableStateOf(ajustes.subirMakerOnline)
        private set
    var marcadaCreality by mutableStateOf(ajustes.subirCreality)
        private set

    fun marcada(web: Web): Boolean = when (web) {
        Web.MAKERONLINE -> marcadaMakerOnline
        Web.CREALITY -> marcadaCreality
    }

    fun marcar(web: Web, si: Boolean) {
        when (web) {
            Web.MAKERONLINE -> {
                marcadaMakerOnline = si
                ajustes.subirMakerOnline = si
            }
            Web.CREALITY -> {
                marcadaCreality = si
                ajustes.subirCreality = si
            }
        }
    }

    /** Si se puede elegir esa web: hay sesión iniciada en ella (pantalla Sesión). */
    fun disponible(web: Web): Boolean = sesion.iniciada(web)

    /** Si se va a subir a esa web: está marcada y tiene sesión. */
    fun sube(web: Web): Boolean = marcada(web) && disponible(web)

    /** Si ya se sabe en qué webs hay sesión (si no, todavía se está comprobando o ha fallado). */
    val sesionComprobada: Boolean get() = sesion.estado != null

    /** Si ahora mismo se está comprobando la sesión (pantalla Sesión). */
    val comprobandoSesion: Boolean get() = ocupacion.tarea == Textos.TAREA_COMPROBANDO

    /** Por qué no se puede elegir una web (en su ficha): no tiene sesión, se está comprobando o sin comprobar. */
    fun motivoNoDisponible(): String = when {
        sesionComprobada -> Textos.SIN_SESION
        comprobandoSesion -> Textos.COMPROBANDO
        else -> Textos.SIN_COMPROBAR
    }

    /** Las webs a las que se va a subir (marcadas y con sesión), para el texto del botón Subir. */
    fun websQueSuben(): List<Web> = Web.entries.filter { sube(it) }

    /**
     * Las insignias de una carpeta, en el orden en que se sube (primero Creality):
     *  - Creality: si ya está o si falló, siempre (es lo que ha pasado); si no, solo si se va a subir allí.
     *  - MakerOnline: solo si se va a subir allí. En pendientes nunca está ya (al subirla, el Uploader
     *    la mueve); si antes tiene que ir a Creality, "espera a Creality".
     * Las carpetas con problemas solo enseñan lo que ya ha pasado.
     */
    fun insignias(c: CarpetaPendiente): List<Insignia> {
        val creality = when {
            c.enCreality -> Insignia(Web.CREALITY, EstadoEnWeb.SUBIDO)
            c.errorCreality != null -> Insignia(Web.CREALITY, EstadoEnWeb.ERROR, c.errorCreality)
            !c.valida || !sube(Web.CREALITY) -> null
            !c.con3mf -> Insignia(Web.CREALITY, EstadoEnWeb.SIN_3MF)
            else -> Insignia(Web.CREALITY, EstadoEnWeb.FALTA)
        }
        val makerOnline = when {
            !c.valida || !sube(Web.MAKERONLINE) -> null
            sube(Web.CREALITY) && c.con3mf && !c.enCreality -> Insignia(Web.MAKERONLINE, EstadoEnWeb.ESPERA)
            else -> Insignia(Web.MAKERONLINE, EstadoEnWeb.FALTA)
        }
        return listOfNotNull(creality, makerOnline)
    }

    /** Vuelve a coger lo que se recuerda (después de traer los datos de antes). */
    fun cargarAjustes() {
        verChrome = ajustes.verChromeAlSubir
        marcadaMakerOnline = ajustes.subirMakerOnline
        marcadaCreality = ajustes.subirCreality
    }

    // ------------------------------------------------------------ ejecución

    var enMarcha by mutableStateOf(false)
        private set
    var parando by mutableStateOf(false)
        private set
    /** "2/5 · llavero-gato" */
    var modeloActual by mutableStateOf<String?>(null)
        private set
    /** El modelo que espera a que pulses Guardar borrador o Descartar, o null. */
    var porRevisar by mutableStateOf<String?>(null)
        private set
    val mensajes = mutableStateListOf<Evento.Mensaje>()
    var resumen by mutableStateOf<SubidaMultiple.Resumen?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val libre: Boolean get() = ocupacion.libre

    /** Para poder parar. Se pide desde la ventana y lo mira core en el hilo del navegador. */
    @Volatile
    private var cancelacion: Cancelacion? = null
    private var revision: RevisionApp? = null

    private val progreso = ProgresoApp(::aplicar)

    /** Vuelve a mirar pendientes/ y comprueba cada carpeta (sin moverla a ningún sitio). */
    fun cargar() {
        val dir = rutas.pendientes
        carpetas = try {
            if (!Files.isDirectory(dir)) {
                emptyList()
            } else {
                Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }.map(::comprobar)
            }
        } catch (e: IOException) {
            error = Textos.errorInesperado(e)
            emptyList()
        }
        if (carpetas.none { it.nombre == seleccionada }) {
            seleccionada = null
            soloSeleccionada = false
        }
    }

    private fun comprobar(dir: Path): CarpetaPendiente {
        val nombre = dir.fileName.toString()
        val portada = try {
            ModelFolder.imagenesDe(dir).firstOrNull()
        } catch (_: Exception) {
            null
        }
        val enCreality = SubidorCreality.yaSubido(dir)
        val errorCreality = dir.resolve(SubidorCreality.ERROR_TXT).takeIf { !enCreality && Files.isRegularFile(it) }
        val con3mf = try {
            ModelFolder.modelosDe(dir).any { it.fileName.toString().lowercase(Locale.ROOT).endsWith(".3mf") }
        } catch (_: Exception) {
            false
        }
        return try {
            val m = ModelFolder.load(dir)
            val meta = m.meta()
            val detalle = meta.title() + " · " + meta.categoryOrDefault().joinToString(" - ") +
                (if (meta.sinCategoria()) Textos.POR_DEFECTO else "")
            CarpetaPendiente(nombre, dir, emptyList(), m.avisos(), detalle, portada, enCreality, errorCreality, con3mf)
        } catch (e: IllegalStateException) {
            CarpetaPendiente(nombre, dir, (e.message ?: e.toString()).lines().filter { it.isNotBlank() },
                portada = portada, enCreality = enCreality, errorCreality = errorCreality, con3mf = con3mf)
        } catch (e: IOException) {
            CarpetaPendiente(nombre, dir, listOf(Textos.errorInesperado(e)),
                portada = portada, enCreality = enCreality, errorCreality = errorCreality, con3mf = con3mf)
        }
    }

    /** Copia a pendientes/ las carpetas arrastradas o elegidas (las originales no se tocan). */
    suspend fun agregarCarpetas(origenes: List<Path>) {
        if (copiando || origenes.isEmpty()) return
        copiando = true
        avisoCarpetas = null
        errorCarpetas = null
        try {
            val r = withContext(Dispatchers.IO) { CopiaCarpetas.copiar(origenes, rutas.pendientes) }
            if (r.copiadas.isNotEmpty()) avisoCarpetas = Textos.carpetasAnadidas(r.copiadas)
            if (r.problemas.isNotEmpty()) errorCarpetas = r.problemas.joinToString("\n")
        } catch (e: CancellationException) {
            throw e // la app se está cerrando: no es un error
        } catch (e: Exception) {
            errorCarpetas = Textos.errorInesperado(e)
        } finally {
            copiando = false
            cargar()
        }
    }

    // ------------------------------------------------------------ ventana Descripción

    /** La ventana Descripción abierta desde la lista ("Agregar descripción"), o null si está cerrada. */
    var descripcion by mutableStateOf<EdicionTextos?>(null)
        private set

    /** Abre la ventana Descripción de una carpeta de la lista, con los textos que ya tenga. */
    fun abrirDescripcion(nombre: String) {
        val c = carpetas.firstOrNull { it.nombre == nombre } ?: return
        avisoCarpetas = null
        errorCarpetas = null
        val l = try {
            TextosCarpeta.leer(c.dir)
        } catch (e: IOException) {
            errorCarpetas = Textos.errorInesperado(e)
            return
        }
        if (l.problema != null && l.archivo == null) { // varios archivos de textos: no se sabe cuál editar
            errorCarpetas = l.problema
            return
        }
        descripcion = EdicionTextos(c.nombre, c.dir, l.archivo, l.problema, FormularioTextos().apply { cargar(l.meta) })
    }

    /** Guarda lo escrito en la carpeta (aunque falte algo) y cierra la ventana. */
    fun guardarDescripcion() {
        val e = descripcion ?: return
        try {
            val destino = TextosCarpeta.guardar(e.dir, e.archivo, e.formulario.aMeta())
            avisoCarpetas = Textos.descripcionGuardada(e.carpeta, destino.fileName.toString())
            descripcion = null
            cargar()
        } catch (ex: Exception) {
            e.error = Textos.errorInesperado(ex)
        }
    }

    fun cerrarDescripcion() {
        descripcion = null
    }

    // ------------------------------------------------------------ ventana Pegar descripción

    /** La ventana "Pegar descripción" abierta, o null si está cerrada. */
    var pegar by mutableStateOf<EdicionPegar?>(null)
        private set

    fun abrirPegar(nombre: String) {
        val c = carpetas.firstOrNull { it.nombre == nombre } ?: return
        avisoCarpetas = null
        errorCarpetas = null
        pegar = EdicionPegar(c.nombre, c.dir)
    }

    fun cerrarPegar() {
        pegar = null
    }

    /**
     * "Rellenar": reparte el texto pegado en sus campos, lo junta con lo que ya tenía la carpeta (lo
     * que el texto no traiga, como la licencia o el tipo, se queda como estaba) y lo abre en la ventana
     * Descripción, para revisarlo y guardarlo. No se guarda nada hasta que la persona pulsa Guardar.
     */
    fun rellenarConPegado() {
        val e = pegar ?: return
        val nuevo = e.leer() ?: return
        val l = try {
            TextosCarpeta.leer(e.dir)
        } catch (ex: IOException) {
            e.error = Textos.errorInesperado(ex)
            return
        }
        if (l.problema != null && l.archivo == null) { // varios archivos de textos: no se sabe cuál usar
            e.error = l.problema
            return
        }
        val antes = l.meta
        val meta = ModelMeta(
            nuevo.title()?.ifBlank { null } ?: antes?.title(),
            nuevo.description()?.ifBlank { null } ?: antes?.description(),
            nuevo.tags().orEmpty().take(InstruccionesIA.MAX_ETIQUETAS).ifEmpty { antes?.tags().orEmpty() },
            if (nuevo.sinCategoria()) antes?.category() else nuevo.category(),
            nuevo.license()?.ifBlank { null } ?: antes?.license(),
            nuevo.printType()?.ifBlank { null } ?: antes?.printType(),
        )
        val sobran = nuevo.tags().orEmpty().size - InstruccionesIA.MAX_ETIQUETAS
        val avisos = InstruccionesIA.revisar(meta) +
            (if (sobran > 0) listOf(Textos.etiquetasRecortadas(nuevo.tags().size)) else emptyList())
        pegar = null
        descripcion = EdicionTextos(
            e.carpeta, e.dir, l.archivo,
            (listOf(Textos.REVISA_PEGADO) + avisos.map { "· $it" }).joinToString("\n"),
            FormularioTextos().apply { cargar(meta) },
        )
    }

    // ------------------------------------------------------------ eliminar una carpeta

    /** La carpeta que espera el "Sí, eliminar", o null. */
    var aEliminar by mutableStateOf<String?>(null)

    /** Quita una carpeta de pendientes: a la papelera de Windows (o a subidas/eliminados/). */
    fun eliminar(nombre: String) {
        aEliminar = null
        avisoCarpetas = null
        errorCarpetas = null
        val c = carpetas.firstOrNull { it.nombre == nombre } ?: return
        try {
            val destino = Papelera.tirar(c.dir, rutas.eliminados)
            avisoCarpetas = if (destino == null) Textos.eliminadaPapelera(nombre) else Textos.eliminadaEn(nombre, destino.toString())
        } catch (e: Exception) {
            errorCarpetas = Textos.noSePuedeEliminar(nombre, e.message?.lineSequence()?.firstOrNull() ?: e.toString())
        } finally {
            cargar()
        }
    }

    // ------------------------------------------------------------ ventana Agregar / Editar modelo

    /** La ventana Agregar modelo / Editar modelo, o null si está cerrada. */
    var ventanaModelo by mutableStateOf<EdicionModelo?>(null)
        private set

    fun abrirModeloNuevo() {
        avisoCarpetas = null
        errorCarpetas = null
        try {
            Files.createDirectories(rutas.pendientes)
            ventanaModelo = EdicionModelo(rutas.pendientes, null)
        } catch (e: Exception) {
            errorCarpetas = Textos.errorInesperado(e)
        }
    }

    /** Abre una carpeta de la lista para cambiarle la imagen, el modelo o la descripción. */
    fun abrirModelo(nombre: String) {
        val c = carpetas.firstOrNull { it.nombre == nombre } ?: return
        avisoCarpetas = null
        errorCarpetas = null
        try {
            ventanaModelo = EdicionModelo(rutas.pendientes, c.dir)
        } catch (e: Exception) {
            errorCarpetas = Textos.errorInesperado(e)
        }
    }

    /** Guardar de la ventana: se queda abierta (para seguir) y la lista se actualiza. */
    fun guardarModelo() {
        val v = ventanaModelo ?: return
        if (v.guardar()) cargar()
    }

    fun cerrarModelo() {
        ventanaModelo = null
        cargar()
    }

    /** Por qué no se puede empezar, o null si se puede. */
    fun problemaOpciones(): String? {
        if (Web.entries.none { sube(it) }) {
            return when {
                sesion.estado == null -> Textos.ERROR_SESION_SIN_COMPROBAR
                Web.entries.none { disponible(it) } -> Textos.ERROR_SIN_SESIONES
                else -> Textos.ERROR_NINGUNA_WEB
            }
        }
        if (soloSeleccionada) {
            val c = carpetas.firstOrNull { it.nombre == seleccionada } ?: return Textos.ERROR_SIN_CARPETA
            if (!c.valida) return Textos.ERROR_CARPETA_CON_PROBLEMAS
        } else if (carpetas.none { it.valida }) {
            return Textos.ERROR_NADA_PARA_SUBIR
        }
        return null
    }

    /**
     * Sube las carpetas válidas (o solo la seleccionada) a las webs marcadas con sesión. Las que tienen
     * problemas no se tocan.
     */
    suspend fun subir() {
        cargar() // por si has cambiado algo en la carpeta desde que se abrió la pantalla
        val problema = problemaOpciones()
        if (problema != null) {
            error = problema
            return
        }
        if (!ocupacion.empezar(Textos.TAREA_SUBIENDO)) return
        reiniciar()
        enMarcha = true
        val nombres = if (soloSeleccionada) listOfNotNull(seleccionada) else carpetas.filter { it.valida }.map { it.nombre }
        val c = Cancelacion()
        cancelacion = c
        val r = RevisionApp(c) { porRevisar = it }
        revision = r
        val opciones = SubidaMultiple.Opciones(
            makerOnline = sube(Web.MAKERONLINE),
            creality = sube(Web.CREALITY),
            revisar = revisar,
            carpetas = nombres,
            verChrome = verChrome,
        )
        try {
            resumen = hilo.ejecutar {
                // Solo un programa con Chrome a la vez: comparten chrome-profile/ con la terminal
                AgentLock.acquire(rutas.candado, "JujaMaker (subiendo)", false, progreso).use {
                    SubidaMultiple(rutas, progreso, c, r).ejecutar(opciones)
                }
            }
        } catch (e: CancellationException) {
            throw e // la app se está cerrando: no es un error
        } catch (e: AgenteException) {
            error = e.message
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
        } finally {
            enMarcha = false
            parando = false
            cancelacion = null
            revision = null
            porRevisar = null
            modeloActual = null
            ocupacion.terminar()
            cargar()
        }
    }

    /** Carpetas de subidos/ (ya en MakerOnline) que aún no están en Creality (ver [subirSubidosACreality]). */
    var subidosSinCreality by mutableStateOf<List<String>>(emptyList())
        private set

    /** Vuelve a contar [subidosSinCreality] (lee las carpetas fuera del hilo de la interfaz). */
    suspend fun contarSubidosSinCreality() {
        subidosSinCreality = withContext(Dispatchers.IO) {
            try {
                SubidorCreality.subidosSinCreality(rutas)
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    /**
     * Sube a Creality las carpetas de subidos/ que ya están en MakerOnline y aún no en Creality. El
     * Uploader de MakerOnline las movió a subidos/, así que la subida normal (de pendientes/) ya no las ve.
     * No se mueven: se apunta subido-creality.txt en cada una, como siempre.
     */
    suspend fun subirSubidosACreality() {
        if (!disponible(Web.CREALITY)) {
            error = motivoNoDisponible()
            return
        }
        val nombres = withContext(Dispatchers.IO) { SubidorCreality.subidosSinCreality(rutas) }
        if (nombres.isEmpty()) {
            error = Textos.CREALITY_SUBIDOS_NADA
            return
        }
        if (!ocupacion.empezar(Textos.TAREA_SUBIENDO)) return
        reiniciar()
        enMarcha = true
        val c = Cancelacion()
        cancelacion = c
        val r = RevisionApp(c) { porRevisar = it }
        revision = r
        try {
            resumen = hilo.ejecutar {
                AgentLock.acquire(rutas.candado, "JujaMaker (subiendo a Creality)", false, progreso).use {
                    val rc = SubidorCreality(rutas, progreso, c, r)
                        .ejecutar(SubidorCreality.Opciones(revisar, nombres, false, verChrome, desdeSubidos = true))
                    SubidaMultiple.Resumen(rc, null, emptyList(), emptyList(), 0, rc.parado)
                }
            }
        } catch (e: CancellationException) {
            throw e // la app se está cerrando: no es un error
        } catch (e: AgenteException) {
            error = e.message
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
        } finally {
            enMarcha = false
            parando = false
            cancelacion = null
            revision = null
            porRevisar = null
            modeloActual = null
            ocupacion.terminar()
            cargar()
            contarSubidosSinCreality()
        }
    }

    /** Botón "Guardar borrador" (MakerOnline) o "Enviar como privado" (Creality) al revisar. */
    fun guardarBorrador() {
        revision?.responder(true)
    }

    /** Botón "Descartar" al revisar: no se guarda y la carpeta se queda en pendientes/. */
    fun descartar() {
        revision?.responder(false)
    }

    /** Botón "Parar": core para en el siguiente punto seguro; lo que no se haya guardado se queda en pendientes/. */
    fun parar() {
        cancelacion?.pedir()
        parando = true
    }

    /** Al cerrar la app: si estaba subiendo, que pare. */
    fun alCerrarApp() {
        cancelacion?.pedir()
    }

    private fun reiniciar() {
        mensajes.clear()
        resumen = null
        error = null
        modeloActual = null
        porRevisar = null
        parando = false
    }

    /** Aplica un aviso de core. Siempre en el hilo de la ventana (ver ProgresoApp). */
    private fun aplicar(e: Evento) {
        when (e) {
            is Evento.Mensaje -> anotar(e)
            is Evento.Modelo -> modeloActual = "${e.indice}/${e.total} · ${e.titulo}"
            else -> Unit // al subir no hay usuarios, páginas, pausas ni captchas
        }
    }

    private fun anotar(m: Evento.Mensaje) {
        mensajes.add(m)
        if (mensajes.size > MAX_MENSAJES) mensajes.removeAt(0)
    }

    private companion object {
        const val MAX_MENSAJES = 2000
    }
}

/**
 * La ventana Descripción abierta para una carpeta de pendientes.
 *
 * @property archivo el .json o .txt donde se guardará; null = se crea info.json
 * @property aviso   algo a tener en cuenta (p. ej. que el archivo actual no se podía leer)
 */
class EdicionTextos(
    val carpeta: String,
    val dir: Path,
    val archivo: Path?,
    val aviso: String?,
    val formulario: FormularioTextos,
) {
    /** Por qué no se ha podido guardar, o null. */
    var error by mutableStateOf<String?>(null)
}
