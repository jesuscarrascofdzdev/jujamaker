package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.InstruccionesIA
import dev.jefe.makeronline.ModelMeta
import java.nio.file.Path

/**
 * La ventana "Pegar descripción" de una carpeta de pendientes: se pega el texto que haya dado una
 * IA (o cualquiera) y se abre en la ventana Descripción, repartido en sus campos, para revisarlo.
 */
class EdicionPegar(val carpeta: String, val dir: Path) {
    var texto by mutableStateOf("")
    /** Por qué no se entiende el texto, o null. */
    var error by mutableStateOf<String?>(null)

    /** Lee el texto pegado; null si no se entiende (el motivo queda en [error]). */
    fun leer(): ModelMeta? = try {
        InstruccionesIA.leerRespuesta(texto).also { error = null }
    } catch (e: IllegalStateException) {
        error = e.message
        null
    }
}
