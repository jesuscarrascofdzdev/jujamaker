package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgentLock
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.Cancelacion
import dev.jefe.makeronline.ModelFolder
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.TextosCarpeta
import dev.jefe.makeronline.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
) {
    val valida: Boolean get() = problemas.isEmpty()
}

/**
 * Lo que enseña la pantalla Subir y lo que hacen sus botones.
 *
 * Todo se cambia desde el hilo de la ventana: las funciones `suspend` vuelven a él al terminar,
 * y los avisos de core llegan a él a través de [ProgresoApp].
 */
class EstadoSubida(
    private val rutas: Rutas,
    private val hilo: HiloNavegador,
    private val ocupacion: Ocupacion,
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
    var resumen by mutableStateOf<Uploader.Resumen?>(null)
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
        return try {
            val m = ModelFolder.load(dir)
            val meta = m.meta()
            val detalle = meta.title() + " · " + meta.categoryOrDefault().joinToString(" - ") +
                (if (meta.sinCategoria()) Textos.POR_DEFECTO else "")
            CarpetaPendiente(nombre, dir, emptyList(), m.avisos(), detalle)
        } catch (e: IllegalStateException) {
            CarpetaPendiente(nombre, dir, (e.message ?: e.toString()).lines().filter { it.isNotBlank() })
        } catch (e: IOException) {
            CarpetaPendiente(nombre, dir, listOf(Textos.errorInesperado(e)))
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
        if (soloSeleccionada) {
            val c = carpetas.firstOrNull { it.nombre == seleccionada } ?: return Textos.ERROR_SIN_CARPETA
            if (!c.valida) return Textos.ERROR_CARPETA_CON_PROBLEMAS
        } else if (carpetas.none { it.valida }) {
            return Textos.ERROR_NADA_PARA_SUBIR
        }
        return null
    }

    /** Sube como borrador las carpetas válidas (o solo la seleccionada). Las que tienen problemas no se tocan. */
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
        val opciones = Uploader.Opciones(revisar, nombres, false)
        try {
            resumen = hilo.ejecutar {
                // Solo un programa con Chrome a la vez: comparten chrome-profile/ con la terminal
                AgentLock.acquire(rutas.candado, "MakerOnline Desktop (subiendo)", false, progreso).use {
                    Uploader(rutas, progreso, c, r).ejecutar(opciones)
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

    /** Botón "Guardar borrador" al revisar. */
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
            else -> Unit // el Uploader no usa usuarios, páginas, pausas ni captchas
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
