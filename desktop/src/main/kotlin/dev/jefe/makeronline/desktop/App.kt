package dev.jefe.makeronline.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** La ventana: la barra de arriba (logo, pestañas y estado) y, debajo, la pantalla elegida. */
@Composable
fun App(app: EstadoApp) {
    // Las tareas con Chrome se lanzan aquí y no dentro de cada pantalla: así siguen en marcha
    // aunque cambies de pantalla (lo de dentro de una pantalla se cancela al salir de ella).
    val alcance = rememberCoroutineScope()
    TemaApp {
        Surface(Modifier.fillMaxSize(), color = Colores.fondo) {
            Column(Modifier.fillMaxSize()) {
                BarraSuperior(app)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (app.pantalla) {
                        Pantalla.SESION -> PantallaSesion(app.sesion, app.datos, app.claude, alcance)
                        Pantalla.SUBIR -> PantallaSubir(app.subida, app.rutas, alcance)
                        Pantalla.DESCARGAR -> PantallaDescargar(app.descarga, app.edicion, alcance)
                    }
                }
            }
        }
    }
}

/**
 * La barra de arriba: a la izquierda el logo, en el centro las pestañas y a la derecha la sesión y
 * lo que está haciendo Chrome (se vea la pantalla que se vea).
 */
@Composable
private fun BarraSuperior(app: EstadoApp) {
    val logo = remember { cargarIcono() }
    Column(Modifier.fillMaxWidth().background(Colores.barra)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Izquierda: logo y nombre
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (logo != null) Image(logo, contentDescription = null, modifier = Modifier.size(30.dp))
                Text(Textos.TITULO_APP, style = MaterialTheme.typography.titleMedium)
            }
            // Centro: las pestañas
            Row(
                Modifier.clip(CircleShape)
                    .background(Colores.tarjeta)
                    .border(1.dp, Colores.bordeSuave, CircleShape)
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (p in Pantalla.entries) {
                    Pestana(p.titulo, iconoDe(p), p == app.pantalla) { app.pantalla = p }
                }
            }
            // Derecha: la sesión y la tarea en marcha
            Row(
                Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val e = app.sesion.estado
                when {
                    e?.iniciada == true -> Etiqueta(
                        e.nombre?.let { Textos.sesionEnBarra(it) } ?: Textos.SESION_INICIADA,
                        Colores.verdeFondo, Colores.verdeTexto, punto = Colores.verde,
                    )
                    e != null -> Etiqueta(Textos.SIN_SESION_BARRA, Colores.rojoFondo, Colores.rojoTexto, punto = Colores.rojo)
                }
                app.ocupacion.tarea?.let { tarea ->
                    val cuenta = app.descarga.indiceUsuario?.let { " $it/${app.descarga.totalUsuarios}" }.orEmpty()
                    Etiqueta(tarea + cuenta, Colores.acentoFondo, Colores.acentoTexto)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.bordeSuave))
    }
}

/** Una pestaña de la barra: icono y nombre; la elegida, en azul. */
@Composable
private fun Pestana(titulo: String, icono: ImageVector, elegida: Boolean, alPulsar: () -> Unit) {
    val color = if (elegida) Colores.sobreAcento else Colores.textoSuave
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (elegida) Colores.acento else Colores.tarjeta)
            .clickable(onClick = alPulsar)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icono, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(
            titulo,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (elegida) FontWeight.SemiBold else FontWeight.Medium,
            color = color,
        )
    }
}

private fun iconoDe(p: Pantalla): ImageVector = when (p) {
    Pantalla.SESION -> Iconos.persona
    Pantalla.SUBIR -> Iconos.subir
    Pantalla.DESCARGAR -> Iconos.descargar
}

/** Abre una dirección en el navegador normal de la persona. */
fun abrirEnNavegador(url: String) {
    try {
        Desktop.getDesktop().browse(URI(url))
    } catch (_: Exception) {
        // sin navegador por defecto: no pasa nada
    }
}

/** Abre una carpeta o un archivo con el programa que tenga asociado Windows. */
fun abrir(ruta: Path) {
    try {
        if (!Files.exists(ruta) && !ruta.fileName.toString().contains('.')) Files.createDirectories(ruta)
        Desktop.getDesktop().open(ruta.toFile())
    } catch (_: Exception) {
        // si no se puede abrir, no pasa nada
    }
}

/** Abre el Explorador con el archivo ya marcado; si no se puede, abre la carpeta donde está. */
fun mostrarArchivo(ruta: Path) {
    try {
        if (System.getProperty("os.name").startsWith("Windows")) {
            ProcessBuilder("explorer.exe", "/select,", ruta.toAbsolutePath().toString()).start()
            return
        }
        if (Desktop.getDesktop().isSupported(Desktop.Action.BROWSE_FILE_DIR)) {
            Desktop.getDesktop().browseFileDirectory(ruta.toFile())
            return
        }
    } catch (_: Exception) {
        // se abre la carpeta, abajo
    }
    ruta.parent?.let { abrir(it) }
}
