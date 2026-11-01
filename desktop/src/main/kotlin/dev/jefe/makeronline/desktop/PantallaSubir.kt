package dev.jefe.makeronline.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jefe.makeronline.Rutas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Pantalla Subir. A la izquierda la lista de pendientes, cada carpeta con su miniatura y sus
 * botones; a la derecha "Subir ahora", el estado de la subida, cómo tiene que ser una carpeta y los
 * mensajes. Arriba, añadir modelos o carpetas.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun PantallaSubir(s: EstadoSubida, rutas: Rutas, alcance: CoroutineScope) {
    // Cada vez que se abre la pantalla se vuelve a mirar pendientes/ (por si has cambiado algo)
    LaunchedEffect(Unit) {
        if (!s.enMarcha) s.cargar()
    }

    // Ventanas: Pegar descripción y Descripción (botones de cada carpeta) y Agregar / Editar modelo
    s.descripcion?.let { e ->
        VentanaDescripcion(
            e.formulario, Textos.tituloDescripcion(e.carpeta), e.aviso, e.error,
            alGuardar = { s.guardarDescripcion() },
            alCerrar = { s.cerrarDescripcion() },
        )
    }
    s.ventanaModelo?.let { m ->
        VentanaModelo(m, alGuardar = { s.guardarModelo() }, alCerrar = { s.cerrarModelo() })
    }
    s.pegar?.let { e ->
        VentanaPegar(e, alRellenar = { s.rellenarConPegado() }, alCerrar = { s.cerrarPegar() })
    }

    // Arrastrar carpetas desde el Explorador a cualquier parte de la pantalla
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
                val archivos = (datos.getTransferData(DataFlavor.javaFileListFlavor) as List<*>)
                    .filterIsInstance<File>().map { it.toPath() }
                alcance.launch { s.agregarCarpetas(archivos) }
                return true
            }
        }
    }

    Column(
        Modifier.fillMaxSize()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { it.awtTransferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor) },
                target = destino,
            )
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Cabecera(Textos.SUBIR_TITULO, Textos.SUBIR_EXPLICACION, Modifier.weight(1f))
            BotonSuave(Textos.BOTON_AGREGAR_MODELO, { s.abrirModeloNuevo() }, enabled = !s.enMarcha)
            BotonSecundario(Textos.ANADIR_CARPETAS, {
                val elegidas = elegirCarpetas(Textos.ELEGIR_CARPETAS)
                if (elegidas.isNotEmpty()) alcance.launch { s.agregarCarpetas(elegidas) }
            }, enabled = !s.copiando)
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            ListaPendientes(s, rutas, encima, Modifier.weight(1f).fillMaxHeight())

            // --- Derecha: subir, estado de la subida, ayuda y mensajes ---
            Column(Modifier.width(340.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    PanelRevision(s)
                    PanelSubirAhora(s, alcance)
                    PanelProgresoSubida(s)
                    PanelResumenSubida(s, rutas)
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    PanelFormato()
                }
                ListaMensajes(s.mensajes, Modifier.height(220.dp))
            }
        }
    }
}

/** Qué se sube, "Revisar antes de guardar" y los botones Subir y Parar. */
@Composable
private fun PanelSubirAhora(s: EstadoSubida, alcance: CoroutineScope) {
    val editable = !s.enMarcha
    Tarjeta(espacio = 8.dp) {
        Text(Textos.SUBIR_AHORA, style = MaterialTheme.typography.titleMedium)
        Text(Textos.QUE_CARPETAS, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        Eleccion(Textos.todasLasValidas(s.carpetas.count { it.valida }), !s.soloSeleccionada, editable) {
            s.soloSeleccionada = false
        }
        Eleccion(Textos.SOLO_LA_SELECCIONADA, s.soloSeleccionada, editable && s.seleccionada != null) {
            s.soloSeleccionada = true
        }
        Casilla(Textos.VER_CHROME_AL_SUBIR, s.verChrome, editable) { s.cambiarVerChrome(it) }
        Casilla(Textos.REVISAR, s.revisar, editable) { s.revisar = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonPrincipal(Textos.SUBIR, { alcance.launch { s.subir() } }, Modifier.weight(1f), enabled = s.libre && !s.copiando)
            BotonParar(Textos.PARAR, { s.parar() }, enabled = s.enMarcha && !s.parando)
        }
        Text(Textos.subirAyuda(s.verChrome, s.revisar), style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    }
}

@Composable
private fun ListaPendientes(s: EstadoSubida, rutas: Rutas, encima: Boolean, modifier: Modifier) {
    val editable = !s.enMarcha
    Tarjeta(modifier, espacio = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Textos.pendientesTitulo(s.carpetas.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            BotonEnlace(Textos.RECARGAR, { s.cargar() }, enabled = editable)
            BotonEnlace(Textos.ABRIR_PENDIENTES, { abrir(rutas.pendientes) })
        }
        // Zona para soltar carpetas (en realidad se pueden soltar en cualquier parte de la pantalla)
        val colorZona = if (encima) Colores.acento else Colores.bordeCampo
        Text(
            if (encima) Textos.SUELTA_AHORA else Textos.SOLTAR_AQUI,
            style = MaterialTheme.typography.bodySmall,
            color = if (encima) Colores.acentoTexto else Colores.textoSuave,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
                .background(if (encima) Colores.acentoFondo else Colores.tarjeta, RoundedCornerShape(10.dp))
                .drawBehind {
                    drawRoundRect(
                        color = colorZona,
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                    )
                }
                .padding(14.dp),
        )
        if (s.copiando) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(Textos.COPIANDO, style = MaterialTheme.typography.bodySmall)
        }
        s.avisoCarpetas?.let { Text(it, color = Colores.verde, style = MaterialTheme.typography.bodySmall) }
        s.errorCarpetas?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (s.carpetas.isEmpty()) {
            Text(Textos.NADA_EN_PENDIENTES, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        }

        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(s.carpetas, key = { it.nombre }) { c -> FilaCarpeta(s, c, editable) }
        }
    }
}

/**
 * Una carpeta de la lista: miniatura, nombre, si está lista o qué le falta, y sus botones.
 * Al pulsarla se selecciona y se ven todos sus problemas y avisos.
 */
@Composable
private fun FilaCarpeta(s: EstadoSubida, c: CarpetaPendiente, editable: Boolean) {
    val elegida = c.nombre == s.seleccionada
    val pequeno = MaterialTheme.typography.bodySmall
    val forma = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth()
            .clip(forma)
            .background(if (elegida) Colores.seleccion else Colores.tarjeta)
            .border(1.dp, if (elegida) Colores.seleccionBorde else Colores.borde, forma)
            .clickable(enabled = editable) { s.seleccionada = if (elegida) null else c.nombre }
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Miniatura(c.portada, c)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    c.nombre,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (c.valida) {
                    Etiqueta(Textos.LISTA_PARA_SUBIR, Colores.verdeFondo, Colores.verdeTexto)
                } else {
                    Etiqueta(Textos.LE_FALTA_ALGO, Colores.rojoFondo, Colores.rojoTexto)
                }
            }
            when {
                c.valida -> c.detalle?.let {
                    Text(
                        it,
                        style = pequeno,
                        color = Colores.textoClaro,
                        maxLines = if (elegida) 4 else 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                elegida -> {
                    for (p in c.problemas) Text("· $p", style = pequeno, color = Colores.rojoTexto)
                }
                else -> Text(
                    "· " + c.problemas.first() + (if (c.problemas.size > 1) Textos.masProblemas(c.problemas.size - 1) else ""),
                    style = pequeno,
                    color = Colores.rojoTexto,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val avisos = if (elegida) c.avisos else c.avisos.take(1)
            for (a in avisos) {
                Text(
                    "⚠ $a",
                    style = pequeno,
                    color = Colores.ambarTexto,
                    maxLines = if (elegida) 3 else 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (s.aEliminar == c.nombre) {
                    // Confirmación antes de eliminar
                    Text(Textos.ELIMINAR_PREGUNTA, style = pequeno, modifier = Modifier.padding(end = 8.dp))
                    BotonEnlace(Textos.SI_ELIMINAR, { s.eliminar(c.nombre) }, peligro = true)
                    BotonEnlace(Textos.NO, { s.aEliminar = null })
                } else {
                    BotonEnlace(Textos.BOTON_PEGAR, { s.abrirPegar(c.nombre) }, enabled = editable)
                    BotonEnlace(Textos.BOTON_DESCRIPCION, { s.abrirDescripcion(c.nombre) }, enabled = editable)
                    BotonEnlace(Textos.BOTON_EDITAR, { s.abrirModelo(c.nombre) }, enabled = editable)
                    BotonEnlace(Textos.BOTON_ELIMINAR, { s.aEliminar = c.nombre }, enabled = editable, peligro = true)
                }
            }
        }
    }
}

/**
 * La portada de la carpeta en pequeño (88×88, recortada al centro). Se lee fuera del hilo de la
 * ventana para no congelarla; mientras tanto, o si no hay portada, un recuadro vacío.
 */
@Composable
private fun Miniatura(ruta: Path?, carpeta: CarpetaPendiente) {
    val imagen by produceState<ImageBitmap?>(null, ruta, carpeta) {
        value = if (ruta == null) null else withContext(Dispatchers.IO) { miniatura(ruta, 176) }
    }
    val forma = RoundedCornerShape(8.dp)
    val img = imagen
    if (img != null) {
        Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp).clip(forma))
    } else {
        Box(
            Modifier.size(88.dp).clip(forma).background(Colores.tarjetaAlta).border(1.dp, Colores.borde, forma),
            contentAlignment = Alignment.Center,
        ) {
            if (ruta == null) Text(Textos.SIN_PORTADA, style = MaterialTheme.typography.labelSmall, color = Colores.textoSuave)
        }
    }
}

/** Lee una imagen y la deja cuadrada de [lado] píxeles (recortando lo que sobra). Null si no se puede leer. */
private fun miniatura(ruta: Path, lado: Int): ImageBitmap? = try {
    val original = ImageIO.read(ruta.toFile())
    if (original == null) {
        null
    } else {
        val corte = minOf(original.width, original.height)
        val x = (original.width - corte) / 2
        val y = (original.height - corte) / 2
        val salida = BufferedImage(lado, lado, BufferedImage.TYPE_INT_RGB)
        val g = salida.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(original, 0, 0, lado, lado, x, y, x + corte, y + corte, null)
        g.dispose()
        salida.toComposeImageBitmap()
    }
} catch (_: Exception) {
    null
}

/** Cómo tiene que ser una carpeta (para quien las prepara fuera de la app y las arrastra). */
@Composable
private fun PanelFormato() {
    Tarjeta(espacio = 8.dp) {
        Text(Textos.COMO_CARPETA, style = MaterialTheme.typography.titleSmall)
        Text(Textos.FORMATO_CARPETAS, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
    }
}

/** Con "Revisar" marcado: el modelo espera a que lo mires en Chrome y elijas. */
@Composable
private fun PanelRevision(s: EstadoSubida) {
    val modelo = s.porRevisar ?: return
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Colores.acentoFondo)
            .border(1.dp, Colores.seleccionBorde, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("⏸ " + Textos.revisarAhora(modelo), style = MaterialTheme.typography.titleMedium, color = Colores.acentoTexto)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BotonPrincipal(Textos.GUARDAR_BORRADOR, { s.guardarBorrador() })
            BotonSecundario(Textos.DESCARTAR, { s.descartar() })
        }
    }
}

@Composable
private fun PanelProgresoSubida(s: EstadoSubida) {
    if (!s.enMarcha) return
    Tarjeta(espacio = 10.dp) {
        Rotulo(Textos.EN_MARCHA)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        s.modeloActual?.let { Text(Textos.modeloActual(it), style = MaterialTheme.typography.titleMedium) }
        if (s.parando) Text(Textos.PARANDO_SUBIDA, color = Colores.rojoTexto)
    }
}

@Composable
private fun PanelResumenSubida(s: EstadoSubida, rutas: Rutas) {
    val r = s.resumen ?: return
    Tarjeta(espacio = 8.dp) {
        if (r.parado()) Text(Textos.RESUMEN_SUBIDA_PARADA, style = MaterialTheme.typography.titleSmall)
        Text(Textos.resumenSubida(r.subidos(), r.conError(), r.invalidos(), r.sinSubir()), color = Colores.textoClaro)
        Row {
            if (r.subidos() > 0) BotonEnlace(Textos.ABRIR_SUBIDOS, { abrir(rutas.subidos) })
            if (r.carpetasConError().isNotEmpty()) BotonEnlace(Textos.ABRIR_ERRORES, { abrir(rutas.errores) })
        }
        for (carpeta in r.carpetasConError()) {
            // La captura si la hay (falló al subir); si no, el error.txt (no era válida)
            val captura = carpeta.resolve("error.png")
            val archivo = if (Files.exists(captura)) captura else carpeta.resolve("error.txt")
            BotonEnlace(Textos.verError(carpeta.fileName.toString()), { abrir(archivo) })
        }
    }
}
