package dev.jefe.makeronline.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import java.awt.datatransfer.DataFlavor
import java.io.File
import java.nio.file.Path

/**
 * Ventana Agregar modelo / Editar modelo: la imagen, el modelo y la descripción de una carpeta
 * de pendientes. Desde aquí se abre la misma ventana Descripción que en la lista.
 */
@Composable
fun VentanaModelo(m: EdicionModelo, alGuardar: () -> Unit, alCerrar: () -> Unit) {
    DialogWindow(
        onCloseRequest = alCerrar,
        state = rememberDialogState(width = 720.dp, height = 680.dp),
        title = m.titulo,
    ) {
        // La ventana Descripción se abre por encima de esta
        if (m.descripcionAbierta) {
            VentanaDescripcion(
                m.formulario, Textos.CAMPO_DESCRIPCION + " · " + m.titulo, aviso = null, error = null,
                alGuardar = { m.descripcionLista() },
                alCerrar = { m.descripcionAbierta = false },
            )
        }
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(m.titulo, style = MaterialTheme.typography.headlineSmall)

                    ZonaArchivo(
                        titulo = Textos.IMAGEN,
                        archivo = m.imagen,
                        vacio = Textos.SIN_IMAGEN,
                        alSoltar = { m.ponerImagen(it) },
                        alElegir = {
                            elegirArchivo(Textos.ELEGIR_IMAGEN, Textos.FILTRO_IMAGEN, *EdicionModelo.EXT_IMAGEN.toTypedArray())
                                ?.let { m.ponerImagen(it) }
                        },
                        alQuitar = { m.quitarImagen() },
                    )
                    ZonaArchivo(
                        titulo = Textos.MODELO,
                        archivo = m.modelo,
                        vacio = Textos.SIN_MODELO,
                        alSoltar = { m.ponerModelo(it) },
                        alElegir = {
                            elegirArchivo(Textos.ELEGIR_MODELO, Textos.FILTRO_MODELO, *EdicionModelo.EXT_MODELO.toTypedArray())
                                ?.let { m.ponerModelo(it) }
                        },
                        alQuitar = { m.quitarModelo() },
                    )
                    if (m.otrosArchivos > 0) {
                        Text(
                            Textos.otrosArchivos(m.otrosArchivos),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // --- Descripción ---
                    Tarjeta {
                        Text(Textos.CAMPO_DESCRIPCION, style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (m.conDescripcion) {
                                listOf(m.formulario.titulo, m.formulario.categoriaElegida.orEmpty())
                                    .filter { it.isNotBlank() }.joinToString(" · ").ifEmpty { "…" }
                            } else {
                                Textos.SIN_DESCRIPCION
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { m.descripcionAbierta = true }) {
                                Text(if (m.conDescripcion) Textos.EDITAR_DESCRIPCION else Textos.AGREGAR_DESCRIPCION)
                            }
                            if (m.conDescripcion) {
                                TextButton(onClick = { m.quitarDescripcion() }) { Text(Textos.QUITAR_DESCRIPCION) }
                            }
                        }
                    }

                    // --- Nombre de la carpeta (la primera vez que se guarda un modelo nuevo) ---
                    if (m.pidiendoNombre) {
                        Tarjeta {
                            Text(Textos.PIDE_NOMBRE, style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(
                                value = m.nombre,
                                onValueChange = { m.nombre = it },
                                label = { Text(Textos.NOMBRE_CARPETA) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // --- Lo que falta, avisos y botones ---
                val faltan = m.faltan()
                if (faltan.isEmpty()) {
                    Text(Textos.MODELO_COMPLETO, color = VERDE)
                } else {
                    Text(Textos.faltaModelo(faltan), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                m.aviso?.let { Text(it, color = VERDE) }
                m.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = alCerrar) { Text(Textos.CERRAR) }
                    Button(onClick = alGuardar) { Text(Textos.GUARDAR) }
                }
            }
        }
    }
}

/** Un recuadro para un archivo: se puede arrastrar encima o elegir con el botón, y quitar. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
private fun ZonaArchivo(
    titulo: String,
    archivo: Path?,
    vacio: String,
    alSoltar: (Path) -> Unit,
    alElegir: () -> Unit,
    alQuitar: () -> Unit,
) {
    var encima by remember { mutableStateOf(false) }
    val destino = remember {
        object : DragAndDropTarget {
            override fun onEntered(event: DragAndDropEvent) {
                encima = true
            }

            override fun onExited(event: DragAndDropEvent) {
                encima = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                encima = false
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                encima = false
                val datos = event.awtTransferable
                if (!datos.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
                val primero = (datos.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
                    .filterIsInstance<File>().firstOrNull() ?: return false
                alSoltar(primero.toPath())
                return true
            }
        }
    }
    Column(
        Modifier.fillMaxWidth()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
                target = destino,
            )
            .border(
                if (encima) 2.dp else 1.dp,
                if (encima) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
            )
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(titulo, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                archivo?.let { "✔ " + it.fileName } ?: vacio,
                color = if (archivo != null) VERDE else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(onClick = alElegir) { Text(Textos.ELEGIR) }
            if (archivo != null) TextButton(onClick = alQuitar) { Text(Textos.QUITAR_ARCHIVO) }
        }
        if (archivo != null) {
            Text(
                archivo.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
