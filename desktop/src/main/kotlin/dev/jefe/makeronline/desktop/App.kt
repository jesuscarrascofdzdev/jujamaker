package dev.jefe.makeronline.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** La ventana: menú a la izquierda y la pantalla elegida a la derecha. */
@Composable
fun App(app: EstadoApp) {
    // Las tareas con Chrome se lanzan aquí y no dentro de cada pantalla: así siguen en marcha
    // aunque cambies de pantalla (lo de dentro de una pantalla se cancela al salir de ella).
    val alcance = rememberCoroutineScope()
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxSize()) {
                Menu(actual = app.pantalla, tarea = app.ocupacion.tarea, alElegir = { app.pantalla = it })
                // línea de separación
                Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when (app.pantalla) {
                        Pantalla.SESION -> PantallaSesion(app.sesion, app.datos, alcance)
                        Pantalla.SUBIR -> PantallaSubir(app.subida, app.rutas, alcance)
                        Pantalla.DESCARGAR -> PantallaDescargar(app.descarga, app.edicion, alcance)
                    }
                }
            }
        }
    }
}

@Composable
private fun Menu(actual: Pantalla, tarea: String?, alElegir: (Pantalla) -> Unit) {
    Column(
        Modifier.width(210.dp).fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            Textos.TITULO_APP,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp, bottom = 16.dp),
        )
        for (p in Pantalla.entries) {
            if (p == actual) {
                FilledTonalButton(onClick = { alElegir(p) }, modifier = Modifier.fillMaxWidth()) { Text(p.titulo) }
            } else {
                TextButton(onClick = { alElegir(p) }, modifier = Modifier.fillMaxWidth()) { Text(p.titulo) }
            }
        }
        // Lo que está haciendo Chrome ahora, se esté en la pantalla que se esté
        if (tarea != null) {
            Text(
                "⏳ $tarea",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp, top = 16.dp),
            )
        }
    }
}

/** Verde para los ✔ (se lee bien en tema claro). */
val VERDE = Color(0xFF1B7F3A)

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

/** Un recuadro con el contenido en columna. */
@Composable
fun Tarjeta(contenido: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = contenido)
    }
}
