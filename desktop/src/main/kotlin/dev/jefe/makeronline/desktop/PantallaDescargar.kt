package dev.jefe.makeronline.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jefe.makeronline.Downloader
import dev.jefe.makeronline.ListaUsuarios
import dev.jefe.makeronline.Web
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pantalla Descargar. Arriba, de qué webs se descarga (una ficha por web, como en Subir). Debajo,
 * en tres columnas: los usuarios de cada web (una pestaña por web, cada una con su lista), las
 * opciones y, a la derecha, lo que está haciendo (o cómo ha ido) y los mensajes.
 * Un solo grupo de botones Simular / Descargar / Parar para todo lo marcado.
 */
@Composable
fun PantallaDescargar(
    d: EstadoDescargas,
    e: EstadoEdicion,
    alcance: CoroutineScope,
    irASesion: () -> Unit,
    /** Las descargas de Printables (con la extensión de Chrome), en su propia tarjeta. */
    printables: EstadoDescargaPrintables? = null,
) {
    // Cada vez que se abre la pantalla se vuelven a leer las listas (por si las has editado)
    LaunchedEffect(Unit) {
        if (!d.enMarcha) d.cargarListas()
        if (printables != null && !printables.enMarcha) printables.cargarLista()
    }
    // La ventana de añadir y quitar usuarios de MakerOnline (se abre con "Editar")
    if (e.abierta) DialogoUsuarios(e, alcance)

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Cabecera(Textos.DESCARGAR_TITULO, Textos.DESCARGAR_EXPLICACION, Modifier.weight(1f))
            Botones(d, alcance)
        }
        PanelPaginas(d, irASesion)

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.width(360.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                PanelUsuarios(d, e, printables)
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
private fun Botones(d: EstadoDescargas, alcance: CoroutineScope) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonSecundario(Textos.SIMULAR, { alcance.launch { d.ejecutar(simular = true) } }, enabled = d.libre)
            BotonPrincipal(
                Textos.botonDescargar(d.websQueDescargan()),
                { alcance.launch { d.ejecutar(simular = false) } },
                enabled = d.libre,
            )
            BotonParar(Textos.PARAR, { d.parar() }, enabled = d.enMarcha && !d.parando)
        }
        Text(Textos.SIMULAR_AYUDA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    }
}

// ------------------------------------------------------------ páginas

/** "Páginas": una ficha por web; se descarga de las marcadas, de izquierda a derecha. */
@Composable
private fun PanelPaginas(d: EstadoDescargas, irASesion: () -> Unit) {
    Tarjeta(espacio = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Textos.PAGINAS, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(Textos.PAGINAS_AYUDA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (web in Web.entries) FichaPagina(d, web, !d.enMarcha, irASesion, Modifier.width(230.dp).fillMaxHeight())
        }
    }
}

/** La ficha de una web: se marca o desmarca; sin sesión, sale con borde de rayas y un enlace a Sesión. */
@Composable
private fun FichaPagina(d: EstadoDescargas, web: Web, editable: Boolean, irASesion: () -> Unit, modifier: Modifier) {
    val forma = RoundedCornerShape(10.dp)
    val pequeno = MaterialTheme.typography.bodySmall
    if (!d.disponible(web)) {
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
            Text(web.nombre, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Colores.textoSuave)
            Text(d.motivoNoDisponible(), style = pequeno, color = Colores.textoSuave)
            if (!d.comprobandoSesion) {
                Text(
                    if (d.sesionComprobada) Textos.INICIAR_SESION_FLECHA else Textos.IR_A_SESION,
                    style = pequeno,
                    color = Colores.enlace,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = irASesion),
                )
            }
        }
        return
    }
    val marcada = d.marcada(web)
    Box(
        modifier.clip(forma)
            .background(if (marcada) Colores.acentoFondo else Colores.tarjetaAlta)
            .border(1.5.dp, if (marcada) Colores.acento else Colores.borde, forma)
            .toggleable(value = marcada, enabled = editable, role = Role.Checkbox) { d.marcar(web, it) }
            .padding(12.dp),
    ) {
        Column(Modifier.padding(end = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
                Iconos.de(web),
                contentDescription = null,
                tint = if (marcada) Colores.acentoTexto else Colores.textoClaro,
                modifier = Modifier.size(22.dp),
            )
            Text(web.nombre, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Colores.texto)
            Text(
                Textos.fichaDescarga(d.marcadosEn(web)),
                style = pequeno,
                color = if (marcada) Colores.acentoTexto else Colores.textoSuave,
            )
        }
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

// ------------------------------------------------------------ usuarios

/** "Usuarios": una pestaña por web y, debajo, la lista de esa web. */
@Composable
private fun PanelUsuarios(d: EstadoDescargas, e: EstadoEdicion, printables: EstadoDescargaPrintables?) {
    // La pestaña de Printables va aparte de las de [Web]: la descarga la hace la extensión (ver EstadoDescargaPrintables)
    val enPrintables = printables?.pestanaActiva == true
    Tarjeta {
        Text(Textos.USUARIOS, style = MaterialTheme.typography.titleMedium)
        Row(
            Modifier.fillMaxWidth()
                .clip(CircleShape)
                .background(Colores.tarjetaAlta)
                .border(1.dp, Colores.bordeSuave, CircleShape)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (web in Web.entries) {
                val activa = web == d.pestana && !enPrintables
                val apagada = !d.descarga(web) // no se va a descargar de ella ahora
                val color = when {
                    activa -> Colores.sobreAcento
                    apagada -> Colores.textoApagado
                    else -> Colores.textoClaro
                }
                Row(
                    Modifier.weight(1f)
                        .clip(CircleShape)
                        .background(if (activa) Colores.acento else Colores.tarjetaAlta)
                        .clickable {
                            d.pestana = web
                            printables?.pestanaActiva = false
                        }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                ) {
                    Icon(Iconos.de(web), contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
                    Text(
                        Textos.pestanaUsuarios(web, d.marcadosEn(web), d.usuariosEn(web)),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (activa) FontWeight.SemiBold else FontWeight.Medium,
                        color = color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (printables != null) PestanaPrintables(printables, enPrintables, Modifier.weight(1f))
        }
        if (printables != null && enPrintables) {
            ContenidoDescargasPrintables(printables, d)
        } else {
            if (!d.descarga(d.pestana)) {
                Text(Textos.pestanaSinDescargar(d.pestana), style = MaterialTheme.typography.bodySmall, color = Colores.ambarTexto)
            }
            when (d.pestana) {
                Web.MAKERONLINE -> UsuariosMakerOnline(d, e)
                Web.CREALITY -> UsuariosCreality(d)
            }
        }
    }
}

/** La lista de usuarios de MakerOnline (usuarios.json), con el creador de la app el primero. */
@Composable
private fun UsuariosMakerOnline(d: EstadoDescargas, e: EstadoEdicion) {
    val m = d.makerOnline
    val editable = !d.enMarcha
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(Textos.usuariosTitulo(m.usuarios.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        BotonEnlace(Textos.RECARGAR, { m.cargarLista() }, enabled = editable)
        // Con usuarios.json roto no se deja editar: se perdería lo que tenga
        BotonEnlace(Textos.EDITAR, { e.abrir() }, enabled = editable && m.errorLista == null)
    }
    m.errorLista?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    // El creador de la app: siempre el primero; se puede desmarcar, pero no quitar
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Colores.acentoFondo)
            .clickable(enabled = editable) { m.cambiarCreador(!m.incluirCreador) }
            .padding(end = 12.dp, top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = m.incluirCreador, onCheckedChange = { m.cambiarCreador(it) }, enabled = editable)
        Column(Modifier.weight(1f).padding(top = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Creador.NOMBRE + " · " + Textos.CREADOR,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                EstadoUsuario(d, Web.MAKERONLINE, Creador.ENTRADA)
            }
            Text(Textos.CREADOR_DETALLE, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
        }
    }

    when {
        !m.listaExiste -> Text(Textos.LISTA_NO_EXISTE, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        m.usuarios.isEmpty() && m.errorLista == null ->
            Text(Textos.LISTA_VACIA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (entrada in m.usuarios) {
            val desc = ListaUsuarios.describir(entrada)
            FilaUsuario(
                d, Web.MAKERONLINE, entrada, desc.nombre, desc.detalle.ifEmpty { null },
                m.marcado(entrada), { m.cambiarMarca(entrada, it) },
            )
        }
    }
    PieLista(d, { m.marcarTodos() }, { m.desmarcarTodos() }, m.marcadosConCreador)
}

/** Una fila de una lista de usuarios: casilla, nombre, detalle y, a la derecha, "ahora", "✓ hecho" o [extra]. */
@Composable
internal fun FilaUsuario(
    d: EstadoDescargas,
    web: Web,
    entrada: String,
    nombre: String,
    detalle: String?,
    marcado: Boolean,
    alMarcar: (Boolean) -> Unit,
    extra: @Composable () -> Unit = {},
) {
    val editable = !d.enMarcha
    val ahora = d.enCurso(web, entrada)
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (ahora) Colores.fila else Colores.tarjeta)
            .clickable(enabled = editable) { alMarcar(!marcado) }
            .padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = marcado, onCheckedChange = alMarcar, enabled = editable)
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(
                nombre,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (ahora) FontWeight.SemiBold else FontWeight.Normal,
                color = if (!marcado || d.hecho(web, entrada)) Colores.textoSuave else Colores.texto,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detalle != null) {
                Text(detalle, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (d.enMarcha) EstadoUsuario(d, web, entrada) else extra()
    }
}

/** "ahora" o "✓ hecho" a la derecha de un usuario, mientras se descarga. */
@Composable
private fun EstadoUsuario(d: EstadoDescargas, web: Web, entrada: String) {
    when {
        d.enCurso(web, entrada) ->
            Text(Textos.USUARIO_AHORA, style = MaterialTheme.typography.bodySmall, color = Colores.acentoTexto)
        d.hecho(web, entrada) ->
            Text(Textos.USUARIO_HECHO, style = MaterialTheme.typography.bodySmall, color = Colores.verde)
    }
}

/** Debajo de cada lista: marcar o desmarcar a todos de golpe y cuántos hay marcados. */
@Composable
internal fun PieLista(d: EstadoDescargas, marcarTodos: () -> Unit, desmarcarTodos: () -> Unit, marcados: Int) {
    val editable = !d.enMarcha
    Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.bordeSuave))
    Row(verticalAlignment = Alignment.CenterVertically) {
        BotonEnlace(Textos.MARCAR_TODOS, marcarTodos, enabled = editable)
        BotonEnlace(Textos.DESMARCAR_TODOS, desmarcarTodos, enabled = editable)
        Spacer(Modifier.weight(1f))
        Text(Textos.marcados(marcados), style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    }
}

// ------------------------------------------------------------ opciones

@Composable
private fun PanelOpciones(d: EstadoDescargas) {
    val editable = !d.enMarcha
    Tarjeta(espacio = 12.dp) {
        Text(Textos.OPCIONES, style = MaterialTheme.typography.titleMedium)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(Textos.ULTIMOS, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CampoPequeno(d.ultimos, { d.cambiarUltimos(it.filter(Char::isDigit).take(4)) }, enabled = editable && !d.todos)
                Text(Textos.ULTIMOS_AYUDA, style = MaterialTheme.typography.bodyMedium, color = Colores.textoSuave)
            }
        }
        Casilla(Textos.TODOS_LOS_MODELOS, d.todos, editable) { d.todos = it }

        // Lo propio de cada web, solo de las que se van a descargar
        for (web in Web.entries.filter { d.descarga(it) }) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.bordeSuave))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Iconos.de(web), contentDescription = null, tint = Colores.textoClaro, modifier = Modifier.size(16.dp))
                Text(web.nombre, style = MaterialTheme.typography.titleSmall)
            }
            when (web) {
                Web.MAKERONLINE -> {
                    Casilla(Textos.PERFIL, d.makerOnline.perfil, editable) { d.makerOnline.perfil = it }
                    CampoMaximo(d.makerOnline.maximo, editable) { d.makerOnline.cambiarMaximo(it) }
                }
                Web.CREALITY -> {
                    Text(Textos.CREALITY_QUE_BAJA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
                    CampoMaximo(d.creality.maximo, editable) { d.creality.cambiarMaximo(it) }
                }
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

/** "Máximo de descargas" de una web (vacío = sin límite). */
@Composable
private fun CampoMaximo(valor: String, editable: Boolean, alCambiar: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(Textos.MAXIMO, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CampoPequeno(valor, { alCambiar(it.filter(Char::isDigit).take(5)) }, enabled = editable)
            Text(Textos.MAXIMO_AYUDA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        }
    }
}

// ------------------------------------------------------------ en marcha y resumen

/** Aviso grande de captcha: la persona tiene que ir a Chrome. */
@Composable
private fun PanelCaptcha(d: EstadoDescargas) {
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
private fun PanelProgreso(d: EstadoDescargas) {
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Rotulo(if (d.simulando) Textos.TAREA_SIMULANDO else Textos.EN_MARCHA)
            d.webEnCurso?.let { web ->
                Icon(Iconos.de(web), contentDescription = null, tint = Colores.acentoTexto, modifier = Modifier.size(16.dp))
                Text(web.nombre, style = MaterialTheme.typography.bodyMedium, color = Colores.acentoTexto)
            }
        }
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
private fun DetalleProgreso(d: EstadoDescargas, ahora: Long) {
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

/** Cómo ha ido: una fila por web (descargados, ya estaban, con error), el zip y los errores. */
@Composable
private fun PanelResumen(d: EstadoDescargas) {
    val zip = d.zip
    if (d.enMarcha || (d.resultados.isEmpty() && zip == null && d.errorZip == null)) return
    val pequeno = MaterialTheme.typography.bodySmall
    Tarjeta(relleno = 20.dp) {
        when {
            d.ultimaParada -> Text(Textos.RESUMEN_PARADO, style = MaterialTheme.typography.titleMedium)
            d.ultimaSimulada -> Text(Textos.RESUMEN_SIMULACION, style = MaterialTheme.typography.titleMedium)
        }
        if (d.resultados.isNotEmpty()) {
            Column {
                FilaResumen(
                    Textos.TABLA_WEB,
                    if (d.ultimaSimulada) Textos.TABLA_SE_DESCARGARIAN else Textos.TABLA_DESCARGADOS,
                    Textos.TABLA_SOLO_LIKE,
                    Textos.TABLA_YA_TENIAN_LIKE,
                    Textos.TABLA_CON_ERROR,
                    cabecera = true,
                )
                for (r in d.resultados) {
                    FilaResumen(
                        r.web.nombre, r.descargados.toString(), r.soloLike.toString(), r.yaTenianLike.toString(), r.errores.toString(),
                    )
                }
            }
            for (r in d.resultados) {
                Textos.notaResumen(r.web, r.saltados)?.let { Text(it, style = pequeno, color = Colores.textoSuave) }
                r.error?.let { Text(Textos.problemaEn(r.web, it), style = pequeno, color = MaterialTheme.colorScheme.error) }
            }
        }
        // El zip con lo descargado (también si se ha parado o ha fallado a mitad)
        if (zip != null) Text("✓ " + Textos.zipCreado(zip.fileName.toString(), d.modelosEnZip), color = Colores.verde)
        d.errorZip?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (zip != null) BotonPrincipal(Textos.MOSTRAR_ZIP, { mostrarArchivo(zip) })
            if (!d.ultimaSimulada && d.resultados.any { it.errores > 0 }) {
                BotonEnlace(Textos.VER_ERRORES, { abrir(d.carpetaErrores) })
            }
        }
    }
}

@Composable
private fun FilaResumen(
    web: String,
    descargados: String,
    soloLike: String,
    yaTenianLike: String,
    conError: String,
    cabecera: Boolean = false,
) {
    val estilo = if (cabecera) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val normal = if (cabecera) Colores.textoSuave else Colores.textoClaro
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(web, style = estilo, color = if (cabecera) Colores.textoSuave else Colores.texto, modifier = Modifier.weight(1.6f))
            Text(descargados, style = estilo, color = normal, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(soloLike, style = estilo, color = normal, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(yaTenianLike, style = estilo, color = normal, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            Text(
                conError,
                style = estilo,
                color = if (!cabecera && conError != "0") Colores.rojoTexto else normal,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.bordeSuave))
    }
}
