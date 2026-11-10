package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.DescargadorCreality
import dev.jefe.makeronline.Downloader
import dev.jefe.makeronline.ListaUsuariosCreality
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.Web

/**
 * La parte de Creality Cloud de la pantalla Descargar: su lista de usuarios
 * (usuarios-creality.json, aparte de la de MakerOnline), a quién se marca y su máximo.
 * Lo de descargar (botones, progreso, zip) es común a todas las webs y está en [EstadoDescargas].
 *
 * Todo se cambia desde el hilo de la ventana.
 */
class EstadoDescargaCreality(
    private val rutas: Rutas,
    private val ajustes: Ajustes,
) {
    // ------------------------------------------------------------ lista de usuarios (usuarios-creality.json)

    var usuarios by mutableStateOf<List<String>>(emptyList())
        private set
    var errorLista by mutableStateOf<String?>(null)
        private set

    /** Los usuarios desmarcados (no se descargan), por su número de usuario. Se recuerda. */
    var desmarcados by mutableStateOf(ajustes.desmarcadosCreality)
        private set

    fun marcado(entrada: String): Boolean = ListaUsuariosCreality.idDe(entrada) !in desmarcados

    val marcados: Int get() = usuarios.count { marcado(it) }

    fun cambiarMarca(entrada: String, marcar: Boolean) {
        val id = ListaUsuariosCreality.idDe(entrada) ?: return
        guardarDesmarcados(if (marcar) desmarcados - id else desmarcados + id)
    }

    fun marcarTodos() = guardarDesmarcados(emptySet())

    fun desmarcarTodos() = guardarDesmarcados(usuarios.mapNotNull { ListaUsuariosCreality.idDe(it) }.toSet())

    private fun guardarDesmarcados(nuevos: Set<Long>) {
        desmarcados = nuevos
        ajustes.desmarcadosCreality = nuevos
    }

    /** Lo que se está escribiendo en el campo de añadir. */
    var texto by mutableStateOf("")
    /** Por qué no se ha podido añadir o quitar, o null. */
    var errorEdicion by mutableStateOf<String?>(null)
        private set
    /** La entrada que se va a quitar (se pregunta antes), o null. */
    var aQuitar by mutableStateOf<String?>(null)

    /** Vuelve a leer usuarios-creality.json. */
    fun cargarLista() {
        val l = ListaUsuariosCreality.leer(rutas.usuariosCreality)
        usuarios = l.entradas
        errorLista = l.error
    }

    /** Añade el usuario escrito en el campo (la dirección de su perfil, con un nombre delante si se quiere). */
    fun agregar() {
        errorEdicion = null
        try {
            ListaUsuariosCreality.agregar(rutas.usuariosCreality, texto.trim())
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
            ListaUsuariosCreality.quitar(rutas.usuariosCreality, entrada)
            ListaUsuariosCreality.idDe(entrada)?.let { guardarDesmarcados(desmarcados - it) }
        } catch (e: AgenteException) {
            errorEdicion = e.message
        } catch (e: Exception) {
            errorEdicion = Textos.errorInesperado(e)
        }
        cargarLista()
    }

    // ------------------------------------------------------------ opciones propias de Creality

    /** Máximo de descargas de Creality de cada vez (texto del campo; vacío = sin límite). Se recuerda. */
    var maximo by mutableStateOf(ajustes.maximoCreality?.toString() ?: "")
        private set

    fun cambiarMaximo(texto: String) {
        maximo = texto
        when {
            texto.isBlank() -> ajustes.maximoCreality = null
            (texto.toIntOrNull() ?: 0) >= 1 -> ajustes.maximoCreality = texto.toInt()
        }
    }

    /** Vuelve a coger lo que se recuerda (después de traer los datos de antes). */
    fun cargarAjustes() {
        desmarcados = ajustes.desmarcadosCreality
        maximo = ajustes.maximoCreality?.toString() ?: ""
    }

    /** Por qué no se puede descargar de Creality con esto, o null si está bien. */
    fun problema(): String? {
        if (maximo.isNotBlank() && (maximo.toIntOrNull() ?: 0) < 1) return Textos.errorMaximo(Web.CREALITY)
        if (errorLista != null) return Textos.CREALITY_ERROR_LISTA_ROTA
        if (usuarios.isEmpty()) return Textos.CREALITY_ERROR_LISTA_VACIA
        if (marcados == 0) return Textos.CREALITY_ERROR_NINGUNO_MARCADO
        return null
    }

    /** Las opciones del descargador con lo elegido aquí y lo común ([ultimos] 0 = todos). */
    fun opciones(ultimos: Int, simular: Boolean, espera: Int, modo: Downloader.Modo, inicioAnterior: Long) =
        DescargadorCreality.Opciones(
            ultimos = ultimos,
            simular = simular,
            max = maximo.toIntOrNull() ?: Int.MAX_VALUE,
            espera = espera,
            modo = modo,
            saltar = usuarios.filterNot { marcado(it) },
            inicioAnterior = inicioAnterior,
        )
}
