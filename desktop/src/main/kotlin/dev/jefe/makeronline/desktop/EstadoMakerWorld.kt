package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.AgenteException
import dev.jefe.makeronline.ColaMakerWorld
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.ServidorMakerWorld
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * La tarjeta de MakerWorld de la pantalla Subir: la cola de modelos para la extensión de Chrome y el
 * servidor por el que la extensión los pide (ver ColaMakerWorld y ServidorMakerWorld en core).
 *
 * A MakerWorld no sube la app: Cloudflare no deja entrar a su Chrome (comprobado el 10/11/2026). Se
 * sube en el Chrome de siempre y la extensión rellena el formulario; la persona revisa y guarda.
 *
 * Al arrancar, copia la extensión (que va dentro de la app, en resources/extension-makerworld) a la
 * carpeta de datos, para que Chrome la pueda cargar desde ahí ("Cargar descomprimida"). Se copia cada
 * vez: así, al actualizar la app, la extensión queda al día (en Chrome basta con pulsar "Actualizar").
 */
class EstadoMakerWorld(private val rutas: Rutas) {
    private val cola = ColaMakerWorld(rutas)
    private var servidor: ServidorMakerWorld? = null

    /** Cuántas carpetas faltan por subir a MakerWorld (de pendientes/ y de subidos/). */
    var porSubir by mutableStateOf(0)
        private set
    /** Carpetas de pendientes/ que no se pueden subir porque les falta algo. */
    var conProblemas by mutableStateOf(0)
        private set
    /** Carpetas que ya están en MakerWorld (tienen subido-makerworld.txt). */
    var subidas by mutableStateOf(0)
        private set
    /** La última que ha apuntado la extensión ("llavero-gato → borrador 123"), o null. */
    var ultima by mutableStateOf<String?>(null)
        private set
    /** Por qué no funciona (el puerto ocupado, no se ha podido copiar la extensión…), o null. */
    var error by mutableStateOf<String?>(null)
        private set

    /** Donde se deja la extensión para cargarla en Chrome. */
    val carpetaExtension: Path get() = rutas.datos.resolve(CARPETA)

    /** Copia la extensión y arranca el servidor. Si algo falla, se apunta en [error] y la app sigue. */
    fun arrancar() {
        try {
            prepararExtension()
        } catch (e: Exception) {
            error = "No he podido copiar la extensión a ${carpetaExtension}: ${e.message}"
        }
        try {
            servidor = ServidorMakerWorld(cola) { clave, borrador ->
                ultima = "${clave.substringAfter('/')} → borrador $borrador"
                actualizar()
            }.also { it.arrancar() }
        } catch (e: AgenteException) {
            error = e.message
        } catch (e: Exception) {
            error = "No he podido arrancar la conexión con la extensión: ${e.message}"
        }
        actualizar()
    }

    /** Vuelve a contar la cola (se llama cada pocos segundos mientras se ve la tarjeta). */
    fun actualizar() {
        try {
            val e = cola.estado()
            porSubir = e.pendientes.size
            conProblemas = e.conProblemas.size
            subidas = e.subidas
        } catch (_: Exception) {
            // si no se puede leer la carpeta ahora, se queda la cuenta anterior
        }
    }

    fun cerrar() {
        try {
            servidor?.close()
        } catch (_: Exception) {
            // al cerrar la app da igual
        }
        servidor = null
    }

    private fun prepararExtension() {
        Files.createDirectories(carpetaExtension)
        for (nombre in ARCHIVOS) {
            val recurso = EstadoMakerWorld::class.java.getResourceAsStream("/$CARPETA/$nombre")
                ?: throw IllegalStateException("falta $nombre dentro de la app")
            recurso.use { Files.copy(it, carpetaExtension.resolve(nombre), StandardCopyOption.REPLACE_EXISTING) }
        }
    }

    private companion object {
        const val CARPETA = "extension-makerworld"
        val ARCHIVOS = listOf("manifest.json", "background.js", "panel.js", "pagina.js")
    }
}
