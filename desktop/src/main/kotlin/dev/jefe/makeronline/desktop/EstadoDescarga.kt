package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.Downloader
import dev.jefe.makeronline.ListaUsuarios
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.Web
import java.nio.file.Files
import java.nio.file.Path

/**
 * La parte de MakerOnline de la pantalla Descargar: su lista de usuarios (usuarios.json), a quién
 * se marca y sus opciones propias (perfil .3mf y máximo). Lo de descargar (botones, progreso, zip)
 * es común a todas las webs y está en [EstadoDescargas].
 *
 * Todo se cambia desde el hilo de la ventana.
 */
class EstadoDescarga(
    private val rutas: Rutas,
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

    /** Cuántos se van a mirar, contando al creador si está marcado. */
    val marcadosConCreador: Int get() = marcados + if (incluirCreador) 1 else 0

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

    // ------------------------------------------------------------ opciones propias de MakerOnline

    /** Descargar también el perfil de impresión (.3mf). */
    var perfil by mutableStateOf(false)

    /** Máximo de descargas de MakerOnline de cada vez (texto del campo; vacío = sin límite). Se recuerda. */
    var maximo by mutableStateOf(ajustes.maximoDescargas?.toString() ?: "")
        private set

    fun cambiarMaximo(texto: String) {
        maximo = texto
        when {
            texto.isBlank() -> ajustes.maximoDescargas = null // vacío = sin límite
            (texto.toIntOrNull() ?: 0) >= 1 -> ajustes.maximoDescargas = texto.toInt()
            // un 0 no vale: no se guarda (al descargar ya se avisa)
        }
    }

    /** Vuelve a coger lo que se recuerda (después de traer los datos de antes). */
    fun cargarAjustes() {
        incluirCreador = ajustes.incluirCreador
        desmarcados = ajustes.desmarcados
        maximo = ajustes.maximoDescargas?.toString() ?: ""
    }

    /** Por qué no se puede descargar de MakerOnline con esto, o null si está bien. */
    fun problema(): String? {
        if (maximo.isNotBlank() && (maximo.toIntOrNull() ?: 0) < 1) return Textos.errorMaximo(Web.MAKERONLINE)
        if (errorLista != null) return Textos.ERROR_LISTA_ROTA
        // Si usuarios.json no existe se deja seguir: el Downloader lo crea con instrucciones
        if (listaExiste && marcados == 0 && !incluirCreador) {
            return if (usuarios.isEmpty()) Textos.ERROR_LISTA_VACIA else Textos.ERROR_NINGUNO_MARCADO
        }
        return null
    }

    /** Las opciones del Downloader con lo elegido aquí y lo común ([ultimos] 0 = todos). */
    fun opciones(ultimos: Int, simular: Boolean, espera: Int, modo: Downloader.Modo): Downloader.Opciones =
        Downloader.Opciones(
            null,
            ultimos,
            simular,
            maximo.toIntOrNull() ?: Int.MAX_VALUE,
            espera,
            perfil,
            modo,
            false,
            // El creador va el primero (si está marcado); los desmarcados de la lista, no
            if (incluirCreador) listOf(Creador.ENTRADA) else emptyList(),
            desmarcados.toList(),
        )

    /** Dónde está el errores.txt de una carpeta de usuario del resumen (se guarda aparte al terminar). */
    fun erroresDe(carpetaUsuario: Path): Path {
        val guardado = rutas.erroresDescargas.resolve(carpetaUsuario.fileName.toString()).resolve("errores.txt")
        return if (Files.exists(guardado)) guardado else carpetaUsuario.resolve("errores.txt")
    }
}
