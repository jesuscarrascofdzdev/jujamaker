package dev.jefe.makeronline.desktop

import dev.jefe.makeronline.Uploader
import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path

/** Eliminar carpetas de pendientes sin perderlas del todo. */
object Papelera {

    /**
     * Manda [dir] a la papelera de Windows (se puede recuperar desde ahí). Si no se puede, la
     * mueve a [eliminados] con la fecha delante.
     *
     * @return null si ha ido a la papelera; si no, dónde ha quedado
     */
    fun tirar(dir: Path, eliminados: Path): Path? {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
                if (Desktop.getDesktop().moveToTrash(dir.toFile())) return null
            }
        } catch (_: Exception) {
            // sin papelera: se mueve a eliminados/
        }
        Files.createDirectories(eliminados)
        return Uploader.mover(dir, eliminados)
    }
}
