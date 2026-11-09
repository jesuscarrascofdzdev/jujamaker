package dev.jefe.makeronline.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Los iconos de la app, dibujados con líneas (24×24). Son pocos: así no hace falta la librería
 * entera de iconos de Material, que pesa varios MB. Se pintan del color que se les pida (Icon(tint)).
 */
object Iconos {
    /** Una persona (Sesión). */
    val persona: ImageVector = icono("persona") {
        circulo(12f, 8f, 4f)
        moveTo(4f, 21f)
        curveTo(5.5f, 17f, 8.5f, 15f, 12f, 15f)
        curveTo(15.5f, 15f, 18.5f, 17f, 20f, 21f)
    }

    /** Flecha hacia arriba sobre una línea (Subir). */
    val subir: ImageVector = icono("subir") {
        moveTo(12f, 16f); lineTo(12f, 4f)
        moveTo(6f, 10f); lineTo(12f, 4f); lineTo(18f, 10f)
        moveTo(4f, 20f); lineTo(20f, 20f)
    }

    /** Flecha hacia abajo sobre una línea (Descargar). */
    val descargar: ImageVector = icono("descargar") {
        moveTo(12f, 4f); lineTo(12f, 16f)
        moveTo(6f, 10f); lineTo(12f, 16f); lineTo(18f, 10f)
        moveTo(4f, 20f); lineTo(20f, 20f)
    }

    /** Un reloj (pausa entre descargas). */
    val reloj: ImageVector = icono("reloj") {
        circulo(12f, 12f, 9f)
        moveTo(12f, 7f); lineTo(12f, 12f); lineTo(15f, 14f)
    }

    /** Un globo terráqueo (Google Chrome). */
    val globo: ImageVector = icono("globo") {
        circulo(12f, 12f, 9f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f)
        curveTo(14.5f, 5.5f, 15.8f, 8.5f, 15.8f, 12f)
        curveTo(15.8f, 15.5f, 14.5f, 18.5f, 12f, 21f)
        curveTo(9.5f, 18.5f, 8.2f, 15.5f, 8.2f, 12f)
        curveTo(8.2f, 8.5f, 9.5f, 5.5f, 12f, 3f)
    }

    /** Un cubo (MakerOnline). */
    val cubo: ImageVector = icono("cubo") {
        moveTo(12f, 3.5f); lineTo(19.5f, 7.75f); lineTo(19.5f, 16.25f); lineTo(12f, 20.5f)
        lineTo(4.5f, 16.25f); lineTo(4.5f, 7.75f); close()
        moveTo(4.5f, 7.75f); lineTo(12f, 12f); lineTo(19.5f, 7.75f)
        moveTo(12f, 12f); lineTo(12f, 20.5f)
    }

    /** Dos eslabones (Conectar con Claude). */
    val enlace: ImageVector = icono("enlace") {
        moveTo(10f, 14f); lineTo(14f, 10f)
        moveTo(9f, 7f); lineTo(11f, 5f)
        curveTo(13f, 3f, 16f, 3f, 18f, 5f)
        curveTo(20f, 7f, 20f, 10f, 18f, 12f)
        lineTo(16f, 14f)
        moveTo(15f, 17f); lineTo(13f, 19f)
        curveTo(11f, 21f, 8f, 21f, 6f, 19f)
        curveTo(4f, 17f, 4f, 14f, 6f, 12f)
        lineTo(8f, 10f)
    }

    /** Una carpeta (carpeta de datos). */
    val carpeta: ImageVector = icono("carpeta") {
        moveTo(3f, 7f)
        curveTo(3f, 5.9f, 3.9f, 5f, 5f, 5f)
        lineTo(9f, 5f); lineTo(11f, 7f); lineTo(19f, 7f)
        curveTo(20.1f, 7f, 21f, 7.9f, 21f, 9f)
        lineTo(21f, 17f)
        curveTo(21f, 18.1f, 20.1f, 19f, 19f, 19f)
        lineTo(5f, 19f)
        curveTo(3.9f, 19f, 3f, 18.1f, 3f, 17f)
        close()
    }

    /** Cuatro esquinas hacia fuera (pasar a pantalla completa). */
    val pantallaCompleta: ImageVector = icono("pantallaCompleta") {
        moveTo(4f, 9f); lineTo(4f, 4f); lineTo(9f, 4f)
        moveTo(15f, 4f); lineTo(20f, 4f); lineTo(20f, 9f)
        moveTo(20f, 15f); lineTo(20f, 20f); lineTo(15f, 20f)
        moveTo(9f, 20f); lineTo(4f, 20f); lineTo(4f, 15f)
    }

    /** Una ventana con su barra de título (pasar a modo ventana). */
    val ventana: ImageVector = icono("ventana") {
        moveTo(5f, 6f); lineTo(19f, 6f); lineTo(19f, 18f); lineTo(5f, 18f); close()
        moveTo(5f, 10f); lineTo(19f, 10f)
    }

    private fun icono(nombre: String, trazos: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(nombre, 24.dp, 24.dp, 24f, 24f)
            .path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = trazos,
            )
            .build()

    /** Un círculo de centro (x, y) y radio r, con dos medias vueltas. */
    private fun PathBuilder.circulo(x: Float, y: Float, r: Float) {
        moveTo(x - r, y)
        arcToRelative(r, r, 0f, true, true, 2 * r, 0f)
        arcToRelative(r, r, 0f, true, true, -2 * r, 0f)
        close()
    }
}
