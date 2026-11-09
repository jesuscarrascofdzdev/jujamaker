package dev.jefe.makeronline.desktop

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Toolkit

/**
 * El modo de la ventana (pantalla completa o ventana) y su tamaño, y lo que se recuerda de ello
 * en ajustes.properties para abrirla igual la próxima vez.
 * "Pantalla completa" es la ventana maximizada, como se ha abierto siempre.
 */
class EstadoVentana(private val ajustes: Ajustes) {
    private val pantalla = Toolkit.getDefaultToolkit().screenSize

    val estado = WindowState(
        placement = if (ajustes.ventanaMaximizada) WindowPlacement.Maximized else WindowPlacement.Floating,
        position = WindowPosition(Alignment.Center),
        size = tamanoGuardado(),
    )

    /** El tamaño de la última vez que estuvo en modo ventana: es al que se vuelve desde pantalla completa. */
    private var tamanoVentana: DpSize = estado.size

    val enVentana: Boolean
        get() = estado.placement == WindowPlacement.Floating

    /** Cambia de pantalla completa a ventana, o al revés, y lo recuerda. */
    fun alternar() {
        if (enVentana) {
            anotarTamano()
            estado.placement = WindowPlacement.Maximized
        } else {
            estado.size = tamanoVentana
            estado.position = WindowPosition(Alignment.Center)
            estado.placement = WindowPlacement.Floating
        }
        guardar()
    }

    /**
     * Apunta el tamaño actual si la ventana está en modo ventana. Si mide tanto como la pantalla, es que
     * se está maximizando y no se apunta, para no volver luego a una "ventana" del tamaño de la pantalla.
     */
    fun anotarTamano() {
        val tamano = estado.size
        if (enVentana && tamano.width.value.toInt() < pantalla.width) tamanoVentana = tamano
    }

    /** Guarda el modo y el tamaño de ventana. Se llama al cambiar de modo y al cerrar la app. */
    fun guardar() {
        anotarTamano()
        ajustes.ventanaMaximizada = !enVentana
        ajustes.tamanoVentana = tamanoVentana.width.value.toInt() to tamanoVentana.height.value.toInt()
    }

    /** El tamaño guardado (o el de siempre), sin pasarse de la pantalla ni quedar demasiado pequeño. */
    private fun tamanoGuardado(): DpSize {
        val (ancho, alto) = ajustes.tamanoVentana ?: (ANCHO_NORMAL to ALTO_NORMAL)
        return DpSize(
            ancho.coerceAtLeast(ANCHO_MINIMO).coerceAtMost(pantalla.width).dp,
            alto.coerceAtLeast(ALTO_MINIMO).coerceAtMost(pantalla.height).dp,
        )
    }

    companion object {
        const val ANCHO_NORMAL = 1280
        const val ALTO_NORMAL = 800
        /** Por debajo de esto la interfaz no cabe. */
        const val ANCHO_MINIMO = 900
        const val ALTO_MINIMO = 600
    }
}
