package dev.jefe.makeronline.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jefe.makeronline.Downloader
import dev.jefe.makeronline.ListaUsuarios
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla Descargar, en tres columnas: los usuarios, las opciones y, a la derecha, lo que está
 * haciendo (o cómo ha ido) y los mensajes. Los botones, arriba a la derecha.
 */
@Composable
fun PantallaDescargar(
    d: EstadoDescarga,
    e: EstadoEdicion,
    alcance: CoroutineScope,
) {
    // Cada vez que se abre la pantalla se vuelve a leer usuarios.json (por si lo has editado)
    LaunchedEffect(Unit) {
        if (!d.enMarcha) d.cargarLista()
    }
    // La ventana de añadir y quitar usuarios (se abre con "Editar")
    if (e.abierta) DialogoUsuarios(e, alcance)

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Cabecera(Textos.DESCARGAR_TITULO, Textos.DESCARGAR_EXPLICACION, Modifier.weight(1f))
            Botones(d, alcance)
        }

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                ListaDeUsuarios(d, e)
            }
            Column(Modifier.width(300.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                PanelOpciones(d)
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                PanelCaptcha(d)
                PanelProgreso(d)
                PanelResumen(d)
                d.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ListaMensajes(d.mensajes, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun Botones(d: EstadoDescarga, alcance: CoroutineScope) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonSecundario(Textos.SIMULAR, { alcance.launch { d.ejecutar(simular = true) } }, enabled = d.libre)
            BotonPrincipal(Textos.DESCARGAR, { alcance.launch { d.ejecutar(simular = false) } }, enabled = d.libre)
            BotonParar(Textos.PARAR, { d.parar() }, enabled = d.enMarcha && !d.parando)
        }
        Text(Textos.SIMULAR_AYUDA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    }
}

@Composable
private fun ListaDeUsuarios(d: EstadoDescarga, e: EstadoEdicion) {
    val editable = !d.enMarcha
    Tarjeta {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Textos.usuariosTitulo(d.usuarios.size), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            BotonEnlace(Textos.RECARGAR, { d.cargarLista() }, enabled = editable)
            // Con usuarios.json roto no se deja editar: se perdería lo que tenga
            BotonEnlace(Textos.EDITAR, { e.abrir() }, enabled = editable && d.errorLista == null)
        }
        d.errorLista?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        // El creador de la app: siempre el primero; se puede desmarcar, pero no quitar
        Row(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(Colores.acentoFondo)
                .clickable(enabled = editable) { d.cambiarCreador(!d.incluirCreador) }
                .padding(end = 12.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(checked = d.incluirCreador, onCheckedChange = { d.cambiarCreador(it) }, enabled = editable)
            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Creador.NOMBRE + " · " + Textos.CREADOR,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    EstadoUsuario(d, Creador.ENTRADA)
                }
                Text(Textos.CREADOR_DETALLE, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
            }
        }

        when {
            !d.listaExiste -> Text(Textos.LISTA_NO_EXISTE, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
            d.usuarios.isEmpty() && d.errorLista == null ->
                Text(Textos.LISTA_VACIA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (entrada in d.usuarios) {
                val desc = ListaUsuarios.describir(entrada)
                val marcado = d.marcado(entrada)
                val ahora = entrada == d.usuarioEnCurso
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (ahora) Colores.fila else Colores.tarjeta)
                        .clickable(enabled = editable) { d.cambiarMarca(entrada, !marcado) }
                        .padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = marcado, onCheckedChange = { d.cambiarMarca(entrada, it) }, enabled = editable)
                    Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(
                            desc.nombre,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (ahora) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (!marcado || entrada in d.usuariosHechos) Colores.textoSuave else Colores.texto,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (desc.detalle.isNotEmpty()) {
                            Text(
                                desc.detalle,
                                style = MaterialTheme.typography.bodySmall,
                                color = Colores.textoSuave,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    EstadoUsuario(d, entrada)
                }
            }
        }

        // Marcar o desmarcar a todos de golpe (también al creador)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.bordeSuave))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotonEnlace(Textos.MARCAR_TODOS, { d.marcarTodos() }, enabled = editable)
            BotonEnlace(Textos.DESMARCAR_TODOS, { d.desmarcarTodos() }, enabled = editable)
            Spacer(Modifier.weight(1f))
            Text(
                Textos.marcados(d.marcados + if (d.incluirCreador) 1 else 0),
                style = MaterialTheme.typography.bodySmall,
                color = Colores.textoSuave,
            )
        }
    }
}

/** "✓ hecho" o "ahora" a la derecha de un usuario, mientras se descarga. */
@Composable
private fun EstadoUsuario(d: EstadoDescarga, entrada: String) {
    when {
        entrada == d.usuarioEnCurso ->
            Text(Textos.USUARIO_AHORA, style = MaterialTheme.typography.bodySmall, color = Colores.acentoTexto)
        entrada in d.usuariosHechos ->
            Text(Textos.USUARIO_HECHO, style = MaterialTheme.typography.bodySmall, color = Colores.verde)
    }
}

@Composable
private fun PanelOpciones(d: EstadoDescarga) {
    val editable = !d.enMarcha
    Tarjeta(espacio = 12.dp) {
        Text(Textos.OPCIONES, style = MaterialTheme.typography.titleMedium)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Textos.ULTIMOS, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CampoPequeno(d.ultimos, { d.ultimos = it.filter(Char::isDigit).take(4) }, enabled = editable && !d.todos)
                Text(Textos.ULTIMOS_AYUDA, style = MaterialTheme.typography.bodyMedium, color = Colores.textoSuave)
            }
        }
        Casilla(Textos.TODOS_LOS_MODELOS, d.todos, editable) { d.todos = it }
        Casilla(Textos.PERFIL, d.perfil, editable) { d.perfil = it }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Textos.MAXIMO, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CampoPequeno(d.maximo, { d.maximo = it.filter(Char::isDigit).take(5) }, enabled = editable)
                Text(Textos.MAXIMO_AYUDA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
            }
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.bordeSuave))
        Text(Textos.VENTANA_CHROME, style = MaterialTheme.typography.titleSmall)
        Eleccion(Textos.MODO_APARTADA, d.modo == Downloader.Modo.APARTADA, editable, Textos.MODO_APARTADA_AYUDA) {
            d.modo = Downloader.Modo.APARTADA
        }
        Eleccion(Textos.MODO_VISIBLE, d.modo == Downloader.Modo.VISIBLE, editable) { d.modo = Downloader.Modo.VISIBLE }
        Eleccion(Textos.MODO_SIN_VENTANA, d.modo == Downloader.Modo.SIN_VENTANA, editable, Textos.MODO_SIN_VENTANA_AYUDA) {
            d.modo = Downloader.Modo.SIN_VENTANA
        }
    }
}

/** Aviso grande de captcha: la persona tiene que ir a Chrome. */
@Composable
private fun PanelCaptcha(d: EstadoDescarga) {
    val aviso = d.captcha ?: return
    Text(
        "⚠ $aviso",
        style = MaterialTheme.typography.titleMedium,
        color = Colores.rojoTexto,
        modifier = Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Colores.rojoFondo)
            .padding(16.dp),
    )
}

@Composable
private fun PanelProgreso(d: EstadoDescarga) {
    if (!d.enMarcha) return

    // Cuenta atrás de la pausa: se refresca cada medio segundo mientras dura
    var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(d.pausaHasta) {
        while (d.pausaHasta != null) {
            ahora = System.currentTimeMillis()
            delay(500)
        }
    }
    Tarjeta(relleno = 20.dp, espacio = 12.dp) {
        Rotulo(if (d.simulando) Textos.TAREA_SIMULANDO else Textos.EN_MARCHA)
        if (d.empaquetando) {
            Text(Textos.CREANDO_ZIP, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(Modifier.fillMaxWidth())
        } else {
            DetalleProgreso(d, ahora)
        }
        if (d.parando) Text(Textos.PARANDO, color = Colores.rojoTexto)
    }
}

/** Usuario y modelo en curso, la barra de usuarios y la cuenta atrás de la pausa. */
@Composable
private fun DetalleProgreso(d: EstadoDescarga, ahora: Long) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        d.usuarioActual?.let {
            Text(Textos.usuarioActual(it), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        val i = d.indiceUsuario
        if (i != null && d.totalUsuarios > 0) {
            Barra(i.toFloat() / d.totalUsuarios, Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        d.paginas?.let { Text(Textos.paginas(it), style = MaterialTheme.typography.bodyMedium, color = Colores.textoClaro) }
        d.modeloActual?.let { Text(Textos.modeloActual(it), fontSize = 15.sp, color = Colores.textoClaro) }
        d.pausaHasta?.let { hasta ->
            val restanteMs = (hasta - ahora).coerceAtLeast(0)
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Colores.tarjetaAlta)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Iconos.reloj, contentDescription = null, tint = Colores.textoClaro, modifier = Modifier.size(18.dp))
                Text(
                    Textos.esperando((restanteMs + 999) / 1000),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Colores.textoClaro,
                    modifier = Modifier.weight(1f),
                )
                if (d.pausaTotal > 0) {
                    Barra(1f - restanteMs.toFloat() / d.pausaTotal, Modifier.width(120.dp), alto = 6.dp, color = Colores.textoSuave)
                }
            }
        }
    }
}

@Composable
private fun PanelResumen(d: EstadoDescarga) {
    val r = d.resumen
    val zip = d.zip
    if (d.enMarcha || (r == null && zip == null && d.errorZip == null)) return
    Tarjeta(relleno = 20.dp) {
        if (r != null) {
            when {
                r.parado() -> Text(Textos.RESUMEN_PARADO, style = MaterialTheme.typography.titleMedium)
                r.simulado() -> Text(Textos.RESUMEN_SIMULACION, style = MaterialTheme.typography.titleMedium)
            }
            if (!r.simulado()) {
                Text(Textos.resumen(r.descargados(), r.soloLike(), r.yaTenianLike(), r.errores()), color = Colores.textoClaro)
            }
        }
        // El zip con lo descargado (también si se ha parado o ha fallado a mitad)
        if (zip != null) Text("✓ " + Textos.zipCreado(zip.fileName.toString(), d.modelosEnZip), color = Colores.verde)
        d.errorZip?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (zip != null) BotonPrincipal(Textos.MOSTRAR_ZIP, { mostrarArchivo(zip) })
            for (carpeta in r?.carpetasConErrores().orEmpty()) {
                BotonEnlace(Textos.VER_ERRORES + " (" + carpeta.fileName + ")", { abrir(d.erroresDe(carpeta)) })
            }
        }
    }
}
