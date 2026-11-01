package dev.jefe.makeronline.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pantalla Sesión: Chrome y MakerOnline arriba, Claude y la carpeta de datos debajo. Si hay datos
 * de antes que traer, una tarjeta encima de todo.
 */
@Composable
fun PantallaSesion(s: EstadoSesion, d: EstadoDatos, c: EstadoClaude, alcance: CoroutineScope) {
    // La primera vez que se abre la pantalla, se comprueba la sesión
    LaunchedEffect(Unit) {
        c.refrescar() // por si se ha cambiado la configuración de Claude mientras tanto
        if (s.estado == null) alcance.launch { s.comprobar() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Cabecera(Textos.SESION_TITULO, Textos.SESION_EXPLICACION)

        // --- Traer los datos de antes (solo si los hay y la app todavía no tiene nada) ---
        d.sugerida?.let { anterior ->
            Tarjeta(relleno = 20.dp) {
                Text(Textos.TRAER_TITULO, style = MaterialTheme.typography.titleMedium)
                Text(Textos.traerExplicacion(anterior.toString()), style = MaterialTheme.typography.bodyMedium, color = Colores.textoClaro)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotonPrincipal(Textos.TRAER, { alcance.launch { d.traer(anterior) } }, enabled = d.libre)
                    BotonSecundario(Textos.TRAER_DE_OTRA, { traerDeOtra(d, alcance) }, enabled = d.libre)
                }
            }
        }
        if (d.trayendo) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(Textos.TRAYENDO, color = Colores.textoClaro)
        }
        d.resumen?.let { Text(it, color = Colores.verde) }
        for (aviso in d.avisos) Text("⚠ $aviso", color = Colores.ambarTexto)
        d.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // --- Chrome y MakerOnline ---
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TarjetaChrome(s, Modifier.weight(1f).fillMaxHeight())
            TarjetaMakerOnline(s, alcance, Modifier.weight(2f).fillMaxHeight())
        }

        // --- Claude y carpeta de datos ---
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TarjetaClaude(c, Modifier.weight(2f).fillMaxHeight())
            TarjetaDatos(d, alcance, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

/** El cuadrado con el icono a la izquierda de cada tarjeta. */
@Composable
private fun IconoTarjeta(icono: ImageVector) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(Colores.tarjetaAlta),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icono, contentDescription = null, tint = Colores.textoClaro, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun TarjetaChrome(s: EstadoSesion, modifier: Modifier) {
    val e = s.estado
    Tarjeta(modifier, relleno = 20.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconoTarjeta(Iconos.globo)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Textos.CHROME, style = MaterialTheme.typography.titleMedium)
                when {
                    e == null -> Text(if (s.ocupado) Textos.COMPROBANDO else Textos.SIN_COMPROBAR, color = Colores.textoSuave)
                    e.chromeInstalado -> Text("✓ " + Textos.CHROME_OK, color = Colores.verdeTexto)
                    else -> {
                        Text("✘ " + Textos.CHROME_FALTA, color = Colores.rojoTexto)
                        BotonEnlace(Textos.DESCARGAR_CHROME, { abrirEnNavegador(Textos.URL_CHROME) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TarjetaMakerOnline(s: EstadoSesion, alcance: CoroutineScope, modifier: Modifier) {
    val e = s.estado
    Tarjeta(modifier, relleno = 20.dp, espacio = 14.dp) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            IconoTarjeta(Iconos.cubo)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(Textos.MAKERONLINE, style = MaterialTheme.typography.titleMedium)
                when {
                    e == null -> Text(if (s.ocupado) Textos.COMPROBANDO else Textos.SIN_COMPROBAR, color = Colores.textoSuave)
                    e.iniciada -> Text(
                        "✓ " + (e.nombre?.let { Textos.sesionIniciadaComo(it) } ?: Textos.SESION_INICIADA),
                        color = Colores.verdeTexto,
                    )
                    else -> Text("✘ " + Textos.sinSesion(e.motivo), color = Colores.rojoTexto)
                }
            }
            if (!s.esperandoLogin) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotonPrincipal(
                        if (e?.iniciada == true) Textos.VOLVER_A_INICIAR else Textos.INICIAR_SESION,
                        { alcance.launch { s.abrirParaIniciar() } },
                        enabled = s.libre && e?.chromeInstalado != false,
                    )
                    BotonSecundario(Textos.COMPROBAR, { alcance.launch { s.comprobar() } }, enabled = s.libre)
                }
            }
        }
        // Chrome abierto para iniciar sesión: qué hacer y los botones para seguir
        if (s.esperandoLogin) {
            Text(Textos.INSTRUCCIONES_LOGIN, style = MaterialTheme.typography.bodyMedium, color = Colores.textoClaro)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BotonPrincipal(Textos.YA_HE_ENTRADO, { alcance.launch { s.yaHeEntrado() } }, enabled = !s.ocupado)
                BotonSecundario(Textos.CANCELAR, { alcance.launch { s.cancelarInicio() } }, enabled = !s.ocupado)
            }
        }
        if (s.ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** Conectar la app con Claude (la aplicación de escritorio) o copiar el comando para Claude Code. */
@Composable
private fun TarjetaClaude(c: EstadoClaude, modifier: Modifier) {
    Tarjeta(modifier, relleno = 20.dp, espacio = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Iconos.enlace, contentDescription = null, tint = Colores.textoClaro, modifier = Modifier.size(22.dp))
            Text(Textos.CLAUDE_TITULO, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (c.estado == ConectorClaude.Estado.CONECTADO) {
                Etiqueta("✓ " + Textos.CLAUDE_ETIQUETA_CONECTADO, Colores.verdeFondo, Colores.verdeTexto)
            }
        }
        Text(Textos.CLAUDE_EXPLICACION, style = MaterialTheme.typography.bodyMedium, color = Colores.textoClaro)
        when {
            c.estado == ConectorClaude.Estado.CONECTADO -> {}
            c.exe == null -> Text(Textos.CLAUDE_SIN_APP, color = Colores.textoSuave)
            c.estado == ConectorClaude.Estado.SIN_CLAUDE -> Text(Textos.CLAUDE_SIN_CLAUDE, color = Colores.textoSuave)
            c.estado == ConectorClaude.Estado.OTRA_RUTA -> Text(Textos.CLAUDE_OTRA_RUTA, color = Colores.ambarTexto)
            else -> Text(Textos.CLAUDE_NO_CONECTADO, color = Colores.textoSuave)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val puede = c.exe != null && c.estado != ConectorClaude.Estado.SIN_CLAUDE
            if (c.estado == ConectorClaude.Estado.CONECTADO) {
                BotonSecundario(Textos.CLAUDE_RECONECTAR, { c.conectar() }, enabled = puede)
            } else {
                BotonPrincipal(Textos.CLAUDE_CONECTAR, { c.conectar() }, enabled = puede)
            }
            if (c.estado == ConectorClaude.Estado.CONECTADO || c.estado == ConectorClaude.Estado.OTRA_RUTA) {
                BotonEnlace(Textos.CLAUDE_QUITAR, { c.quitar() })
            }
            BotonEnlace(Textos.CLAUDE_COPIAR_COMANDO, { c.copiarComando() }, enabled = c.exe != null)
        }
        c.aviso?.let { Text(it, color = Colores.verde) }
        c.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(c.archivo.toString(), fontFamily = LETRA_MONO, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    }
}

/** Dónde guarda la app sus datos, y traerlos de otra carpeta. */
@Composable
private fun TarjetaDatos(d: EstadoDatos, alcance: CoroutineScope, modifier: Modifier) {
    Tarjeta(modifier, relleno = 20.dp, espacio = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Iconos.carpeta, contentDescription = null, tint = Colores.textoClaro, modifier = Modifier.size(22.dp))
            Text(Textos.CARPETA_DATOS_TITULO, style = MaterialTheme.typography.titleMedium)
        }
        Text(d.carpeta.toString(), fontFamily = LETRA_MONO, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonSecundario(Textos.ABRIR_CARPETA_DATOS, { abrir(d.carpeta) })
            if (d.sugerida == null) BotonEnlace(Textos.TRAER_DE_OTRA, { traerDeOtra(d, alcance) }, enabled = d.libre)
        }
    }
}

/** Elige la carpeta donde estaban los datos (la que tiene chrome-profile y usuarios.json) y los trae. */
private fun traerDeOtra(d: EstadoDatos, alcance: CoroutineScope) {
    val elegida = elegirCarpetas(Textos.ELEGIR_CARPETA_ANTERIOR).firstOrNull() ?: return
    alcance.launch { d.traer(elegida) }
}
