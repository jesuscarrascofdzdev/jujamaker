package dev.jefe.makeronline.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.TooltipArea
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
import androidx.compose.foundation.layout.width
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
import dev.jefe.makeronline.Web
import java.awt.Desktop
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** La ventana: la barra de arriba (logo, pestañas y estado) y, debajo, la pantalla elegida. */
@Composable
fun App(app: EstadoApp, ventana: EstadoVentana) {
    // Las tareas con Chrome se lanzan aquí y no dentro de cada pantalla: así siguen en marcha
    // aunque cambies de pantalla (lo de dentro de una pantalla se cancela al salir de ella).
    val alcance = rememberCoroutineScope()
    TemaApp {
        Surface(Modifier.fillMaxSize(), color = Colores.fondo) {
            Column(Modifier.fillMaxSize()) {
                BarraSuperior(app, ventana)
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (app.pantalla) {
                        Pantalla.SESION -> PantallaSesion(app.sesion, app.datos, app.claude, alcance)
                        Pantalla.SUBIR -> PantallaSubir(app.subida, app.rutas, alcance, irASesion = { app.pantalla = Pantalla.SESION }, printables = app.printables)
                        Pantalla.DESCARGAR -> PantallaDescargar(app.descargas, app.edicion, alcance, irASesion = { app.pantalla = Pantalla.SESION })
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
private fun BarraSuperior(app: EstadoApp, ventana: EstadoVentana) {
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
                // Las sesiones, en una sola etiqueta (cuando ya se han comprobado); al pulsarla, a Sesión
                if (app.sesion.estado != null) EtiquetaSesiones(app.sesion) { app.pantalla = Pantalla.SESION }
                app.ocupacion.tarea?.let { tarea ->
                    val d = app.descargas
                    val cuenta = d.indiceUsuario?.let { " " + (d.webEnCurso?.let { w -> Textos.webCorta(w) + " " } ?: "") + "$it/${d.totalUsuarios}" }
                        .orEmpty()
                    Etiqueta(tarea + cuenta, Colores.acentoFondo, Colores.acentoTexto)
                }
                BotonModoVentana(ventana)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.bordeSuave))
    }
}

/**
 * "Sesiones: 1 de 2", con un punto por web (verde con sesión, rojo sin ella): verde si están todas,
 * ámbar si falta alguna y rojo si no hay ninguna. Al pasar el ratón enseña cada web y, al pulsarla,
 * lleva a la pantalla Sesión.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EtiquetaSesiones(s: EstadoSesion, alPulsar: () -> Unit) {
    val con = Web.entries.count { s.iniciada(it) }
    val total = Web.entries.size
    val fondo = when (con) {
        total -> Colores.verdeFondo
        0 -> Colores.rojoFondo
        else -> Colores.ambarFondo
    }
    val color = when (con) {
        total -> Colores.verdeTexto
        0 -> Colores.rojoTexto
        else -> Colores.ambarTexto
    }
    TooltipArea(
        tooltip = {
            val forma = RoundedCornerShape(10.dp)
            Column(
                Modifier.width(260.dp)
                    .clip(forma)
                    .background(Colores.tarjetaAlta)
                    .border(1.dp, Colores.bordeCampo, forma)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (web in Web.entries) {
                    val si = s.iniciada(web)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (si) Colores.verde else Colores.rojo))
                        Text(web.nombre, style = MaterialTheme.typography.bodySmall, color = Colores.texto, modifier = Modifier.weight(1f))
                        Text(
                            Textos.estadoEnBarra(si, s.cuenta(web)),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (si) Colores.verdeTexto else Colores.rojoTexto,
                        )
                    }
                }
            }
        },
    ) {
        Row(
            Modifier.clip(CircleShape)
                .background(fondo)
                .clickable(onClick = alPulsar)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (web in Web.entries) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (s.iniciada(web)) Colores.verde else Colores.rojo))
            }
            Text(
                Textos.sesionesEnBarra(con, total),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                modifier = Modifier.padding(start = 2.dp),
            )
        }
    }
}

/** El botón de la derecha de la barra: pasa de pantalla completa a ventana y al revés (con su texto de ayuda). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BotonModoVentana(ventana: EstadoVentana) {
    val enVentana = ventana.enVentana
    val texto = if (enVentana) Textos.PASAR_A_PANTALLA_COMPLETA else Textos.PASAR_A_VENTANA
    TooltipArea(
        tooltip = {
            val forma = RoundedCornerShape(6.dp)
            Text(
                texto,
                style = MaterialTheme.typography.bodySmall,
                color = Colores.texto,
                modifier = Modifier.clip(forma)
                    .background(Colores.tarjeta)
                    .border(1.dp, Colores.bordeSuave, forma)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        },
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).clickable(onClick = ventana::alternar),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (enVentana) Iconos.pantallaCompleta else Iconos.ventana,
                contentDescription = texto,
                tint = Colores.textoSuave,
                modifier = Modifier.size(18.dp),
            )
        }
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
