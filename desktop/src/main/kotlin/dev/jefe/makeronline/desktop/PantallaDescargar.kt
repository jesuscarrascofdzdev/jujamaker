package dev.jefe.makeronline.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jefe.makeronline.Downloader
import dev.jefe.makeronline.ListaUsuarios
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Textos.DESCARGAR_TITULO, style = MaterialTheme.typography.headlineSmall)
        Text(Textos.DESCARGAR_EXPLICACION, style = MaterialTheme.typography.bodyMedium)

        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // --- Izquierda: usuarios y opciones ---
            Column(
                Modifier.width(370.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ListaDeUsuarios(d, e)
                PanelOpciones(d)
            }
            // --- Derecha: botones, progreso y mensajes ---
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Botones(d, alcance)
                PanelProgreso(d)
                PanelResumen(d)
                d.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                ListaMensajes(d.mensajes, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ListaDeUsuarios(d: EstadoDescarga, e: EstadoEdicion) {
    val editable = !d.enMarcha
    Tarjeta {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Textos.usuariosTitulo(d.usuarios.size), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            TextButton(enabled = editable, onClick = { d.cargarLista() }) { Text(Textos.RECARGAR) }
            // Con usuarios.json roto no se deja editar: se perdería lo que tenga
            TextButton(enabled = editable && d.errorLista == null, onClick = { e.abrir() }) { Text(Textos.EDITAR) }
        }
        d.errorLista?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        // El creador de la app: siempre el primero; se puede desmarcar, pero no quitar
        Row(
            Modifier.fillMaxWidth()
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = d.incluirCreador, onCheckedChange = { d.cambiarCreador(it) }, enabled = editable)
            Column {
                Text(
                    Creador.NOMBRE + " · " + Textos.CREADOR,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    Textos.CREADOR_DETALLE,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
        when {
            !d.listaExiste -> Text(Textos.LISTA_NO_EXISTE, style = MaterialTheme.typography.bodySmall)
            d.usuarios.isEmpty() && d.errorLista == null ->
                Text(Textos.LISTA_VACIA, style = MaterialTheme.typography.bodySmall)
        }
        for (entrada in d.usuarios) {
            val desc = ListaUsuarios.describir(entrada)
            val elegido = entrada == d.seleccionado
            Column(
                Modifier.fillMaxWidth()
                    .background(if (elegido) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)
                    .clickable(enabled = editable) { d.seleccionado = if (elegido) null else entrada }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(desc.nombre, style = MaterialTheme.typography.bodyMedium)
                if (desc.detalle.isNotEmpty()) {
                    Text(desc.detalle, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Eleccion(Textos.TODOS_LOS_USUARIOS, !d.soloSeleccionado, editable) { d.soloSeleccionado = false }
        Eleccion(Textos.SOLO_SELECCIONADO, d.soloSeleccionado, editable && d.seleccionado != null) {
            d.soloSeleccionado = true
        }
    }
}

@Composable
private fun PanelOpciones(d: EstadoDescarga) {
    val editable = !d.enMarcha
    Tarjeta {
        Text(Textos.OPCIONES, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = d.ultimos,
                onValueChange = { d.ultimos = it.filter(Char::isDigit).take(4) },
                label = { Text(Textos.ULTIMOS) },
                singleLine = true,
                enabled = editable && !d.todos,
                modifier = Modifier.width(150.dp),
            )
            Text(Textos.ULTIMOS_AYUDA, style = MaterialTheme.typography.bodySmall)
        }
        Casilla(Textos.TODOS_LOS_MODELOS, d.todos, editable) { d.todos = it }
        Casilla(Textos.PERFIL, d.perfil, editable) { d.perfil = it }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = d.maximo,
                onValueChange = { d.maximo = it.filter(Char::isDigit).take(5) },
                label = { Text(Textos.MAXIMO) },
                singleLine = true,
                enabled = editable,
                modifier = Modifier.width(150.dp),
            )
            Text(Textos.MAXIMO_AYUDA, style = MaterialTheme.typography.bodySmall)
        }
        Text(Textos.VENTANA_CHROME, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Eleccion(Textos.MODO_APARTADA, d.modo == Downloader.Modo.APARTADA, editable) { d.modo = Downloader.Modo.APARTADA }
        Eleccion(Textos.MODO_VISIBLE, d.modo == Downloader.Modo.VISIBLE, editable) { d.modo = Downloader.Modo.VISIBLE }
        Eleccion(Textos.MODO_SIN_VENTANA, d.modo == Downloader.Modo.SIN_VENTANA, editable) {
            d.modo = Downloader.Modo.SIN_VENTANA
        }
    }
}

@Composable
private fun Botones(d: EstadoDescarga, alcance: CoroutineScope) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(enabled = d.libre, onClick = { alcance.launch { d.ejecutar(simular = true) } }) {
            Text(Textos.SIMULAR)
        }
        Button(enabled = d.libre, onClick = { alcance.launch { d.ejecutar(simular = false) } }) {
            Text(Textos.DESCARGAR)
        }
        Button(
            enabled = d.enMarcha && !d.parando,
            onClick = { d.parar() },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
        ) {
            Text(Textos.PARAR)
        }
        Text(Textos.SIMULAR_AYUDA, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PanelProgreso(d: EstadoDescarga) {
    // Aviso grande de captcha: la persona tiene que ir a Chrome
    d.captcha?.let { aviso ->
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(
                "⚠ $aviso",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
    if (!d.enMarcha) return

    // Cuenta atrás de la pausa: se refresca cada medio segundo mientras dura
    var ahora by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(d.pausaHasta) {
        while (d.pausaHasta != null) {
            ahora = System.currentTimeMillis()
            delay(500)
        }
    }
    Tarjeta {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        d.usuarioActual?.let { Text(Textos.usuarioActual(it), style = MaterialTheme.typography.titleSmall) }
        d.paginas?.let { Text(Textos.paginas(it)) }
        d.modeloActual?.let { Text(Textos.modeloActual(it)) }
        d.pausaHasta?.let { hasta ->
            val restante = ((hasta - ahora + 999) / 1000).coerceAtLeast(0)
            Text(Textos.esperando(restante), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (d.parando) Text(Textos.PARANDO, color = MaterialTheme.colorScheme.error)
        if (d.empaquetando) Text(Textos.CREANDO_ZIP, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun PanelResumen(d: EstadoDescarga) {
    val r = d.resumen
    val zip = d.zip
    if (d.enMarcha || (r == null && zip == null && d.errorZip == null)) return
    Tarjeta {
        if (r != null) {
            when {
                r.parado() -> Text(Textos.RESUMEN_PARADO, style = MaterialTheme.typography.titleSmall)
                r.simulado() -> Text(Textos.RESUMEN_SIMULACION, style = MaterialTheme.typography.titleSmall)
            }
            if (!r.simulado()) Text(Textos.resumen(r.descargados(), r.soloLike(), r.yaTenianLike(), r.errores()))
        }
        // El zip con lo descargado (también si se ha parado o ha fallado a mitad)
        if (zip != null) Text("✔ " + Textos.zipCreado(zip.fileName.toString(), d.modelosEnZip), color = VERDE)
        d.errorZip?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (zip != null) Button(onClick = { mostrarArchivo(zip) }) { Text(Textos.MOSTRAR_ZIP) }
            for (carpeta in r?.carpetasConErrores().orEmpty()) {
                TextButton(onClick = { abrir(d.erroresDe(carpeta)) }) {
                    Text(Textos.VER_ERRORES + " (" + carpeta.fileName + ")")
                }
            }
        }
    }
}
