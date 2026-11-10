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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jefe.makeronline.ColaMakerWorld
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.Web
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
 * Pantalla Subir. A la izquierda la lista de pendientes, cada carpeta con su miniatura, sus
 * insignias (en qué webs está o le falta estar) y sus botones; a la derecha "Subir ahora" (con una
 * ficha por web), el estado de la subida, cómo tiene que ser una carpeta y los mensajes. Arriba,
 * añadir modelos o carpetas.
 *
 * @param irASesion lleva a la pantalla Sesión (desde la ficha de una web sin sesión)
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun PantallaSubir(
    s: EstadoSubida,
    rutas: Rutas,
    alcance: CoroutineScope,
    irASesion: () -> Unit,
    makerWorld: EstadoMakerWorld,
) {
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
            // Siempre a mano, también mientras sube: abre subidas/errores, con lo que no se ha podido subir
            BotonSecundario(Textos.VER_ERRORES_SUBIDA, { abrir(rutas.errores) })
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
                    PanelSubirAhora(s, alcance, irASesion)
                    PanelProgresoSubida(s)
                    PanelResumenSubida(s, rutas)
                    PanelMakerWorld(makerWorld)
                    s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    PanelFormato()
                }
                ListaMensajes(s.mensajes, Modifier.height(220.dp))
            }
        }
    }
}

/**
 * Dónde se sube (una ficha por web, de dos en dos; solo se puede marcar si tiene sesión), qué
 * carpetas, "Revisar" y los botones Subir y Parar.
 */
@Composable
private fun PanelSubirAhora(s: EstadoSubida, alcance: CoroutineScope, irASesion: () -> Unit) {
    val editable = !s.enMarcha
    Tarjeta(espacio = 8.dp) {
        Text(Textos.SUBIR_AHORA, style = MaterialTheme.typography.titleMedium)
        Text(Textos.DONDE_SUBIR, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        for (fila in Web.entries.chunked(2)) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (web in fila) FichaWeb(s, web, editable, irASesion, Modifier.weight(1f).fillMaxHeight())
                if (fila.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(4.dp))
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
            BotonPrincipal(
                Textos.botonSubir(s.websQueSuben()),
                { alcance.launch { s.subir() } },
                Modifier.weight(1f),
                enabled = s.libre && !s.copiando,
            )
            BotonParar(Textos.PARAR, { s.parar() }, enabled = s.enMarcha && !s.parando)
        }
        Text(Textos.subirAyuda(s.verChrome, s.revisar), style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    }
}

/**
 * La ficha de una web en "Dónde": se pulsa para marcarla o desmarcarla (se recuerda). Sin sesión,
 * con el borde de rayas, el motivo y un enlace a la pantalla Sesión.
 */
@Composable
private fun FichaWeb(s: EstadoSubida, web: Web, editable: Boolean, irASesion: () -> Unit, modifier: Modifier) {
    val forma = RoundedCornerShape(10.dp)
    val estiloNombre = MaterialTheme.typography.bodyMedium
    val pequeno = MaterialTheme.typography.bodySmall
    if (!s.disponible(web)) {
        Column(
            modifier.clip(forma)
                .drawBehind {
                    drawRoundRect(
                        color = Colores.bordeCampo,
                        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))),
                        cornerRadius = CornerRadius(10.dp.toPx()),
                    )
                }
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Iconos.de(web), contentDescription = null, tint = Colores.textoApagado, modifier = Modifier.size(22.dp))
            Text(web.nombre, style = estiloNombre, fontWeight = FontWeight.SemiBold, color = Colores.textoSuave)
            Text(s.motivoNoDisponible(), style = pequeno, color = Colores.textoSuave)
            // Mientras se comprueba la sesión, no hace falta ir a ningún sitio
            if (!s.comprobandoSesion) {
                Text(
                    if (s.sesionComprobada) Textos.INICIAR_SESION_FLECHA else Textos.IR_A_SESION,
                    style = pequeno,
                    color = Colores.enlace,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = irASesion),
                )
            }
        }
        return
    }
    val marcada = s.marcada(web)
    Box(
        modifier.clip(forma)
            .background(if (marcada) Colores.acentoFondo else Colores.tarjetaAlta)
            .border(1.5.dp, if (marcada) Colores.acento else Colores.borde, forma)
            .toggleable(value = marcada, enabled = editable, role = Role.Checkbox) { s.marcar(web, it) }
            .padding(12.dp),
    ) {
        Column(Modifier.padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                Iconos.de(web),
                contentDescription = null,
                tint = if (marcada) Colores.acentoTexto else Colores.textoClaro,
                modifier = Modifier.size(22.dp),
            )
            Text(web.nombre, style = estiloNombre, fontWeight = FontWeight.SemiBold, color = Colores.texto)
            Text(Textos.fichaWeb(web), style = pequeno, color = if (marcada) Colores.acentoTexto else Colores.textoSuave)
        }
        // La marca de "elegida", arriba a la derecha
        if (marcada) {
            Box(
                Modifier.align(Alignment.TopEnd).size(20.dp).clip(CircleShape).background(Colores.acento),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Iconos.check, contentDescription = null, tint = Colores.sobreAcento, modifier = Modifier.size(14.dp))
            }
        }
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
            // En qué webs está ya, en cuáles falló y adónde le falta ir
            val insignias = s.insignias(c)
            if (insignias.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    for (i in insignias) InsigniaWeb(i)
                }
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
 * Una insignia de una carpeta ("Creality · subido"): verde si ya está, roja si falló (al pulsarla se
 * abre el error) y gris si le falta.
 */
@Composable
private fun InsigniaWeb(i: Insignia) {
    val fondo = when (i.estado) {
        EstadoEnWeb.SUBIDO -> Colores.verdeFondo
        EstadoEnWeb.ERROR -> Colores.rojoFondo
        else -> Colores.tarjetaAlta
    }
    val color = when (i.estado) {
        EstadoEnWeb.SUBIDO -> Colores.verdeTexto
        EstadoEnWeb.ERROR -> Colores.rojoTexto
        else -> Colores.textoSuave
    }
    var m = Modifier.clip(CircleShape).background(fondo)
    if (i.estado != EstadoEnWeb.SUBIDO && i.estado != EstadoEnWeb.ERROR) m = m.border(1.dp, Colores.borde, CircleShape)
    i.archivo?.let { archivo -> m = m.clickable { abrir(archivo) } }
    Row(
        m.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(Iconos.de(i.web), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(Textos.insignia(i.web, i.estado), style = MaterialTheme.typography.bodySmall, color = color)
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
        // El modelo llega como "flower · Creality Cloud": en Creality se envía (privado), en MakerOnline se guarda el borrador
        val enviar = if (modelo.endsWith(Web.CREALITY.nombre)) Textos.ENVIAR_PRIVADO else Textos.GUARDAR_BORRADOR
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BotonPrincipal(enviar, { s.guardarBorrador() })
            BotonSecundario(Textos.DESCARTAR, { s.descartar() })
        }
    }
}

/**
 * MakerWorld: se sube a mano en el Chrome de siempre con la extensión de JujaMaker, que rellena el
 * formulario con los modelos de la cola (ver EstadoMakerWorld). Aquí se ve cuántos faltan.
 */
@Composable
private fun PanelMakerWorld(mw: EstadoMakerWorld) {
    // Mientras se ve la tarjeta, la cuenta se refresca sola (la extensión va apuntando lo que se sube)
    LaunchedEffect(Unit) {
        while (true) {
            mw.actualizar()
            delay(5_000)
        }
    }
    Tarjeta(espacio = 10.dp) {
        Text(Textos.MW_TITULO, style = MaterialTheme.typography.titleMedium)
        Text(Textos.MW_EXPLICACION, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        val error = mw.error
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error)
        } else {
            Text(Textos.mwCuenta(mw.porSubir, mw.conProblemas, mw.subidas), style = MaterialTheme.typography.bodyMedium)
        }
        mw.ultima?.let { Text("✓ " + Textos.mwUltima(it), style = MaterialTheme.typography.bodySmall, color = Colores.verdeTexto) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonPrincipal(Textos.MW_ABRIR, { abrirEnNavegador(ColaMakerWorld.FORMULARIO_SEGUIDO) }, enabled = mw.porSubir > 0)
            BotonEnlace(Textos.MW_CARPETA_EXTENSION, { abrir(mw.carpetaExtension) })
        }
        Text(Textos.MW_INSTALAR, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
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

/**
 * Cómo ha ido la última subida: una tabla con una fila por web (subidos, con error y sin subir) y,
 * debajo, lo que conviene saber.
 */
@Composable
private fun PanelResumenSubida(s: EstadoSubida, rutas: Rutas) {
    val r = s.resumen ?: return
    val pequeno = MaterialTheme.typography.bodySmall
    val creality = r.creality
    val makerOnline = r.makerOnline
    val conError = makerOnline?.carpetasConError().orEmpty()
    Tarjeta(espacio = 10.dp) {
        Text(Textos.ULTIMA_SUBIDA, style = MaterialTheme.typography.titleMedium)
        if (r.parado) Text(Textos.RESUMEN_SUBIDA_PARADA, style = MaterialTheme.typography.titleSmall)
        Column {
            FilaTabla(Textos.TABLA_WEB, Textos.TABLA_SUBIDOS, Textos.TABLA_CON_ERROR, Textos.TABLA_SIN_SUBIR, cabecera = true)
            // Las que solo tienen .stl cuentan como "sin subir" en Creality (y se dice cuáles debajo)
            if (creality != null || r.sin3mf.isNotEmpty()) {
                FilaTabla(
                    Web.CREALITY.nombre,
                    (creality?.subidos ?: 0).toString(),
                    (creality?.conError ?: 0).toString(),
                    ((creality?.sinSubir ?: 0) + r.sin3mf.size).toString(),
                )
            }
            if (makerOnline != null || r.sinMakerOnline.isNotEmpty()) {
                FilaTabla(
                    Web.MAKERONLINE.nombre,
                    (makerOnline?.subidos() ?: 0).toString(),
                    ((makerOnline?.conError() ?: 0) + (makerOnline?.invalidos() ?: 0)).toString(),
                    ((makerOnline?.sinSubir() ?: 0) + r.sinMakerOnline.size).toString(),
                )
            }
        }
        if (creality != null && creality.yaSubidos > 0) {
            Text(Textos.yaEstabanEnCreality(creality.yaSubidos), style = pequeno, color = Colores.textoSuave)
        }
        if (r.sin3mf.isNotEmpty()) Text(Textos.sinCrealityPorStl(r.sin3mf), style = pequeno, color = Colores.ambarTexto)
        if (r.sinMakerOnline.isNotEmpty()) Text(Textos.sinMakerOnline(r.sinMakerOnline), style = pequeno, color = Colores.ambarTexto)
        if (r.aSubidos > 0) Text(Textos.pasanASubidos(r.aSubidos), style = pequeno, color = Colores.textoClaro)
        Row {
            if ((makerOnline?.subidos() ?: 0) > 0 || r.aSubidos > 0) BotonEnlace(Textos.ABRIR_SUBIDOS, { abrir(rutas.subidos) })
            if (conError.isNotEmpty()) BotonEnlace(Textos.ABRIR_ERRORES, { abrir(rutas.errores) })
        }
        if ((creality?.conError ?: 0) > 0) Text(Textos.ERRORES_CREALITY_EN_LISTA, style = pequeno, color = Colores.textoSuave)
        for (carpeta in conError) {
            // La captura si la hay (falló al subir); si no, el error.txt (no era válida)
            val captura = carpeta.resolve("error.png")
            val archivo = if (Files.exists(captura)) captura else carpeta.resolve("error.txt")
            BotonEnlace(Textos.verError(carpeta.fileName.toString()), { abrir(archivo) })
        }
    }
}

/**
 * Una fila de la tabla del resumen: la web y sus tres cifras, alineadas a la derecha. Lo que no es
 * cero en "Con error" sale en rojo y en "Sin subir", en ámbar. [cabecera]: los títulos, en gris.
 */
@Composable
private fun FilaTabla(web: String, subidos: String, conError: String, sinSubir: String, cabecera: Boolean = false) {
    val estilo = if (cabecera) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val normal = if (cabecera) Colores.textoSuave else Colores.textoClaro
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(web, style = estilo, color = if (cabecera) Colores.textoSuave else Colores.texto, modifier = Modifier.weight(1.6f))
            Text(subidos, style = estilo, color = normal, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(
                conError,
                style = estilo,
                color = if (!cabecera && conError != "0") Colores.rojoTexto else normal,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            Text(
                sinSubir,
                style = estilo,
                color = if (!cabecera && sinSubir != "0") Colores.ambarTexto else normal,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.bordeSuave))
    }
}
