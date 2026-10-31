package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.ModelFolder
import dev.jefe.makeronline.TextosCarpeta
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * La ventana Agregar modelo / Editar modelo: una carpeta de pendientes con su imagen, su modelo
 * (.3mf o .stl) y su descripción. Se puede guardar aunque falte algo; la primera vez que se
 * guarda un modelo nuevo se pide el nombre de la carpeta.
 *
 * Los archivos que se eligen se copian a la carpeta al guardar (los originales no se tocan).
 * Lo que se quita se borra de la carpeta al guardar.
 *
 * @param pendientes la carpeta subidas/pendientes/
 * @param existente  la carpeta a editar, o null para un modelo nuevo
 */
class EdicionModelo(private val pendientes: Path, existente: Path?) {

    /** La carpeta del modelo; null hasta que se guarda un modelo nuevo por primera vez. */
    var carpeta by mutableStateOf(existente)
        private set

    /** La imagen (en la carpeta o la recién elegida), o null si no hay. */
    var imagen by mutableStateOf<Path?>(null)
        private set
    /** El .3mf o .stl (en la carpeta o el recién elegido), o null si no hay. */
    var modelo by mutableStateOf<Path?>(null)
        private set
    /** Cuántas imágenes o modelos más tiene la carpeta (de momento no se tocan). */
    var otrosArchivos by mutableStateOf(0)
        private set

    val formulario = FormularioTextos()
    /** Si el modelo tiene descripción (o se ha escrito una en esta ventana). */
    var conDescripcion by mutableStateOf(false)
        private set
    var descripcionAbierta by mutableStateOf(false)

    /** Para pedir el nombre de la carpeta la primera vez que se guarda un modelo nuevo. */
    var pidiendoNombre by mutableStateOf(false)
        private set
    var nombre by mutableStateOf("")

    var aviso by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    // Lo que hay ahora en la carpeta, para saber qué copiar y qué borrar al guardar
    private var imagenGuardada: Path? = null
    private var modeloGuardado: Path? = null
    private var textosGuardados: Path? = null
    private var descripcionCambiada = false

    init {
        if (existente != null) leer(existente)
    }

    val titulo: String get() = carpeta?.let { Textos.editarModelo(it.fileName.toString()) } ?: Textos.AGREGAR_MODELO

    private fun leer(dir: Path) {
        val imagenes = ModelFolder.imagenesDe(dir)
        val modelos = ModelFolder.modelosDe(dir)
        imagen = imagenes.firstOrNull()
        modelo = modelos.firstOrNull()
        otrosArchivos = (imagenes.size - 1).coerceAtLeast(0) + (modelos.size - 1).coerceAtLeast(0)
        imagenGuardada = imagen
        modeloGuardado = modelo
        val l = TextosCarpeta.leer(dir)
        textosGuardados = l.archivo
        formulario.cargar(l.meta)
        conDescripcion = l.archivo != null
        if (l.problema != null) error = l.problema
    }

    // ------------------------------------------------------------ lo que hace cada botón

    /** Elige (o arrastra) una imagen. Devuelve false si no es .jpg, .jpeg o .png. */
    fun ponerImagen(archivo: Path): Boolean {
        if (extension(archivo) !in EXT_IMAGEN) {
            error = Textos.NO_ES_IMAGEN
            return false
        }
        imagen = archivo
        limpiarAvisos()
        return true
    }

    fun quitarImagen() {
        imagen = null
        limpiarAvisos()
    }

    /** Elige (o arrastra) el modelo. Devuelve false si no es .3mf o .stl. */
    fun ponerModelo(archivo: Path): Boolean {
        if (extension(archivo) !in EXT_MODELO) {
            error = Textos.NO_ES_MODELO
            return false
        }
        modelo = archivo
        limpiarAvisos()
        return true
    }

    fun quitarModelo() {
        modelo = null
        limpiarAvisos()
    }

    /** "Guardar" de la ventana Descripción: lo escrito se queda aquí hasta guardar el modelo. */
    fun descripcionLista() {
        conDescripcion = true
        descripcionCambiada = true
        descripcionAbierta = false
        limpiarAvisos()
    }

    fun quitarDescripcion() {
        formulario.cargar(null)
        conDescripcion = false
        descripcionCambiada = true
        limpiarAvisos()
    }

    /** Lo que le falta para poder subirlo (se puede guardar igual). */
    fun faltan(): List<String> = buildList {
        if (imagen == null) add(Textos.FALTA_IMAGEN)
        if (modelo == null) add(Textos.FALTA_MODELO)
        if (!conDescripcion) add(Textos.FALTA_DESCRIPCION) else addAll(formulario.faltan())
    }

    /**
     * Guarda todo en la carpeta. Si es un modelo nuevo, la primera vez pide el nombre
     * ([pidiendoNombre]) y no guarda hasta que se confirme con otro "Guardar".
     * Devuelve true si se ha guardado.
     */
    fun guardar(): Boolean {
        limpiarAvisos()
        val dir = carpeta ?: crearCarpeta() ?: return false
        return try {
            imagenGuardada = sustituir(imagenGuardada, imagen, dir, portada = true)
            imagen = imagenGuardada
            modeloGuardado = sustituir(modeloGuardado, modelo, dir, portada = false)
            modelo = modeloGuardado
            if (descripcionCambiada) {
                if (conDescripcion) {
                    textosGuardados = TextosCarpeta.guardar(dir, textosGuardados, formulario.aMeta())
                } else {
                    textosGuardados?.let { Files.deleteIfExists(it) }
                    textosGuardados = null
                }
                descripcionCambiada = false
            }
            aviso = Textos.modeloGuardado(dir.fileName.toString())
            true
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
            false
        }
    }

    /** Crea la carpeta con el nombre escrito; la primera vez solo pide el nombre. */
    private fun crearCarpeta(): Path? {
        if (!pidiendoNombre) {
            pidiendoNombre = true
            return null
        }
        val n = nombre.trim()
        val problema = when {
            n.isEmpty() -> Textos.NOMBRE_VACIO
            n.any { it in "\\/:*?\"<>|" } -> Textos.NOMBRE_CARACTERES
            n.endsWith(".") -> Textos.NOMBRE_PUNTO
            Files.exists(pendientes.resolve(n)) -> Textos.nombreRepetido(n)
            else -> null
        }
        if (problema != null) {
            error = problema
            return null
        }
        return try {
            val dir = Files.createDirectories(pendientes.resolve(n))
            carpeta = dir
            pidiendoNombre = false
            dir
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
            null
        }
    }

    /**
     * Deja en la carpeta el archivo [nuevo] en lugar de [anterior]: borra el anterior si ya no se
     * usa y copia el nuevo si viene de fuera. La imagen se copia como "portada.<ext>", así es
     * la portada aunque haya más imágenes.
     */
    private fun sustituir(anterior: Path?, nuevo: Path?, dir: Path, portada: Boolean): Path? {
        if (nuevo == anterior) return anterior
        if (anterior != null && anterior.parent == dir) Files.deleteIfExists(anterior)
        if (nuevo == null) return null
        if (nuevo.parent == dir) return nuevo
        val nombreDestino = if (portada) "portada." + extension(nuevo) else nuevo.fileName.toString()
        return Files.copy(nuevo, dir.resolve(nombreDestino), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun limpiarAvisos() {
        aviso = null
        error = null
    }

    companion object {
        val EXT_IMAGEN = setOf("jpg", "jpeg", "png")
        val EXT_MODELO = setOf("3mf", "stl")

        fun extension(p: Path): String = p.fileName.toString().substringAfterLast('.', "").lowercase()
    }
}
