package dev.jefe.makeronline.desktop

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** El portapapeles de Windows. */
object Portapapeles {

    /** El texto copiado, o null si no hay texto (o el portapapeles lo tiene ocupado otro programa). */
    fun leerTexto(): String? = try {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    } catch (_: Exception) {
        null
    }

    /** Copia un texto; false si no se ha podido (el portapapeles lo tiene ocupado otro programa). */
    fun copiarTexto(texto: String): Boolean = try {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(texto), null)
        true
    } catch (_: Exception) {
        false
    }
}
