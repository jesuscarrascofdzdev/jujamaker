package dev.jefe.makeronline.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
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
import dev.jefe.makeronline.Web
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Pantalla Sesión: arriba, una tarjeta con las webs donde se sube (una fila cada una, con su sesión
 * y su botón); debajo, Chrome, la carpeta de datos y Claude. Si hay datos de antes que traer, una
 * tarjeta encima de todo.
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

        // --- Las webs donde se sube ---
        TarjetaWebs(s, alcance)

        // --- Chrome, carpeta de datos y Claude ---
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TarjetaChrome(s, Modifier.weight(1f).fillMaxHeight())
            TarjetaDatos(d, alcance, Modifier.weight(1.4f).fillMaxHeight())
            TarjetaClaude(c, Modifier.weight(2f).fillMaxHeight())
        }
    }
}

/**
 * Las webs donde se sube, una fila cada una (ver [FilaWeb]). Arriba, cuándo se comprobó la sesión
 * y el botón para comprobarlas todas otra vez. Una web nueva sale aquí sola: basta con añadirla a
 * Web (core) y darle su icono (Iconos.de) y sus textos (Textos.comoSube e instruccionesLogin).
 */
@Composable
private fun TarjetaWebs(s: EstadoSesion, alcance: CoroutineScope) {
    val forma = RoundedCornerShape(12.dp)
    val comprobando = s.ocupado && s.webEsperando == null
    Column(Modifier.fillMaxWidth().clip(forma).background(Colores.tarjeta).border(1.dp, Colores.borde, forma)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(Textos.WEBS_TITULO, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            val cuando = if (comprobando) Textos.COMPROBANDO else s.comprobadaA?.let { Textos.comprobadoA(it) }
            cuando?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave) }
            BotonSecundario(Textos.COMPROBAR, { alcance.launch { s.comprobar() } }, enabled = s.libre)
        }
        if (comprobando) LinearProgressIndicator(Modifier.fillMaxWidth())
        // Los errores que no son de una web concreta (por ejemplo, otro programa está usando Chrome)
        if (s.errorDe == null) {
            s.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
        }
        for (web in Web.entries) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Colores.bordeSuave))
            FilaWeb(s, web, alcance)
        }
    }
}

/**
 * Una web: su icono, cómo sube, si hay sesión y el botón para iniciarla. Mientras Chrome está abierto
 * para iniciar sesión en ella, debajo salen las instrucciones con "Ya he entrado" y "Cancelar".
 */
@Composable
private fun FilaWeb(s: EstadoSesion, web: Web, alcance: CoroutineScope) {
    val e = s.estado
    val iniciada = s.iniciada(web)
    val esperando = s.esperandoLogin && s.webEsperando == web
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            IconoTarjeta(Iconos.de(web))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(web.nombre, style = MaterialTheme.typography.titleMedium)
                Text(Textos.comoSube(web), style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
                // Por qué no hay sesión (MakerOnline lo dice) o, en Creality, que se comprueba al subir
                val nota = when {
                    e == null -> null
                    web == Web.MAKERONLINE && !iniciada -> e.motivo?.replaceFirstChar { it.uppercase() }
                    web == Web.CREALITY && iniciada -> Textos.CREALITY_NOTA
                    else -> null
                }
                nota?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave) }
            }
            when {
                e == null -> Etiqueta(
                    if (s.ocupado) Textos.COMPROBANDO else Textos.SIN_COMPROBAR,
                    Colores.tarjetaAlta, Colores.textoSuave, punto = Colores.textoApagado,
                )
                iniciada -> Etiqueta(
                    Textos.sesionIniciadaCorta(s.cuenta(web)),
                    Colores.verdeFondo, Colores.verdeTexto, punto = Colores.verde,
                )
                else -> Etiqueta(Textos.SIN_SESION, Colores.rojoFondo, Colores.rojoTexto, punto = Colores.rojo)
            }
            // Todos los botones con el mismo ancho, para que queden en columna
            Box(Modifier.width(200.dp), contentAlignment = Alignment.CenterEnd) {
                if (iniciada) {
                    BotonEnlace(Textos.VOLVER_A_INICIAR, { alcance.launch { s.abrirParaIniciar(web) } }, enabled = s.libre)
                } else {
                    BotonPrincipal(
                        Textos.INICIAR_SESION,
                        { alcance.launch { s.abrirParaIniciar(web) } },
                        enabled = s.libre && e?.chromeInstalado != false,
                    )
                }
            }
        }
        // Chrome abierto para iniciar sesión en esta web: qué hacer y los botones para seguir
        if (esperando) {
            val forma = RoundedCornerShape(10.dp)
            Column(
                Modifier.padding(start = 56.dp).fillMaxWidth()
                    .clip(forma)
                    .background(Colores.acentoFondo)
                    .border(1.dp, Colores.seleccionBorde, forma)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(Textos.instruccionesLogin(web), style = MaterialTheme.typography.bodyMedium, color = Colores.textoClaro)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BotonPrincipal(Textos.YA_HE_ENTRADO, { alcance.launch { s.yaHeEntrado() } }, enabled = !s.ocupado)
                    BotonSecundario(Textos.CANCELAR, { alcance.launch { s.cancelarInicio() } }, enabled = !s.ocupado)
                }
            }
        }
        if (s.ocupado && s.webEsperando == web) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (s.errorDe == web) s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

/** El cuadrado con el icono de cada web. */
@Composable
private fun IconoTarjeta(icono: ImageVector) {
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Colores.tarjetaAlta),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icono, contentDescription = null, tint = Colores.textoClaro, modifier = Modifier.size(22.dp))
    }
}

/** El título de las tarjetas pequeñas: icono y nombre. */
@Composable
private fun TituloTarjeta(icono: ImageVector, titulo: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icono, contentDescription = null, tint = Colores.textoClaro, modifier = Modifier.size(22.dp))
        Text(titulo, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun TarjetaChrome(s: EstadoSesion, modifier: Modifier) {
    val e = s.estado
    Tarjeta(modifier, relleno = 20.dp, espacio = 12.dp) {
        TituloTarjeta(Iconos.globo, Textos.CHROME)
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

/** Conectar la app con Claude (la aplicación de escritorio) o copiar el comando para Claude Code. */
@Composable
private fun TarjetaClaude(c: EstadoClaude, modifier: Modifier) {
    Tarjeta(modifier, relleno = 20.dp, espacio = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TituloTarjeta(Iconos.enlace, Textos.CLAUDE_TITULO, Modifier.weight(1f))
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
        }
        // En su propia línea: la tarjeta es estrecha y no cabe al lado de los otros botones
        BotonEnlace(Textos.CLAUDE_COPIAR_COMANDO, { c.copiarComando() }, enabled = c.exe != null)
        c.aviso?.let { Text(it, color = Colores.verde) }
        c.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(c.archivo.toString(), fontFamily = LETRA_MONO, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    }
}

/** Dónde guarda la app sus datos, y traerlos de otra carpeta. */
@Composable
private fun TarjetaDatos(d: EstadoDatos, alcance: CoroutineScope, modifier: Modifier) {
    Tarjeta(modifier, relleno = 20.dp, espacio = 12.dp) {
        TituloTarjeta(Iconos.carpeta, Textos.CARPETA_DATOS_TITULO)
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
