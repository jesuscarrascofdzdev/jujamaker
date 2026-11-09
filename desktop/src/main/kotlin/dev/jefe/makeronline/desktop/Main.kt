package dev.jefe.makeronline.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import java.awt.Dimension
import javax.imageio.ImageIO
import javax.swing.UIManager

fun main(args: Array<String>) {
    // "JujaMaker.exe --mcp": el conector para Claude Desktop o Claude Code, sin ventanas
    if ("--mcp" in args) {
        ServidorMcp.arrancar()
        return
    }
    // Para que la ventana de elegir carpetas se vea como las de Windows
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (_: Exception) {
        // si no se puede, se ve con el aspecto de Java: funciona igual
    }
    val app = EstadoApp(EstadoApp.rutas())
    val icono = cargarIcono()
    // Se abre como se dejó la última vez; la primera vez, maximizada (con el asistente a la izquierda hace falta anchura)
    val ventana = EstadoVentana(app.ajustes)
    application {
        Window(
            onCloseRequest = {
                ventana.guardar()
                app.cerrar() // cierra Chrome si estaba abierto
                exitApplication()
            },
            title = Textos.TITULO_APP,
            icon = icono,
            state = ventana.estado,
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(EstadoVentana.ANCHO_MINIMO, EstadoVentana.ALTO_MINIMO)
            }
            // Mientras se cambia el tamaño en modo ventana, se va apuntando para recordarlo al cerrar
            LaunchedEffect(Unit) {
                snapshotFlow { ventana.estado.size }.collect { ventana.anotarTamano() }
            }
            App(app, ventana)
        }
    }
}

/** El icono de la ventana y de la barra de tareas (src/main/resources/icono.png). Si no está, el de Java. */
internal fun cargarIcono(): Painter? = try {
    EstadoApp::class.java.getResourceAsStream("/icono.png")?.use { ImageIO.read(it) }
        ?.let { BitmapPainter(it.toComposeImageBitmap()) }
} catch (_: Exception) {
    null
}
