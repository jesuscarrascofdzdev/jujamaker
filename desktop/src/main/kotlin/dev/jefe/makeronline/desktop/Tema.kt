package dev.jefe.makeronline.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// El aspecto de toda la app (tema oscuro, letra IBM Plex Sans) y las piezas que lo usan:
// botones, tarjetas, etiquetas, barras de progreso y campos pequeños.

/** Los colores de la app. Si cambias uno aquí, cambia en todas las pantallas. */
object Colores {
    val fondo = Color(0xFF111318)
    val barra = Color(0xFF15171C)
    val tarjeta = Color(0xFF1A1D24)
    val tarjetaAlta = Color(0xFF20242D)
    val consola = Color(0xFF0F1115)
    val borde = Color(0xFF2D323E)
    val bordeSuave = Color(0xFF2A2E38)
    val bordeCampo = Color(0xFF3A4050)
    val texto = Color(0xFFE6E8EC)
    val textoClaro = Color(0xFFC6CBD4)
    val textoSuave = Color(0xFFA0A8B6)
    val textoApagado = Color(0xFF6B7383)
    val acento = Color(0xFF6B9BFF)
    val sobreAcento = Color(0xFF0B1020)
    val acentoFondo = Color(0xFF1E2A44)
    val acentoTexto = Color(0xFFB5CCFF)
    val acentoApagado = Color(0xFF26324D)
    val enlace = Color(0xFF8DB2FF)
    val seleccion = Color(0xFF1C2333)
    val seleccionBorde = Color(0xFF34466F)
    val fila = Color(0xFF232836)
    val verde = Color(0xFF4CC38A)
    val verdeTexto = Color(0xFF7FE0A8)
    val verdeFondo = Color(0xFF17291F)
    val rojo = Color(0xFFFF7B70)
    val rojoTexto = Color(0xFFFFA49B)
    val rojoFondo = Color(0xFF3A1D1B)
    val ambarTexto = Color(0xFFF0C47A)
    val ambarFondo = Color(0xFF3A2C14)
}

/** Verde para los ✓ (se lee bien sobre el fondo oscuro). */
val VERDE = Colores.verde

/** La letra de la app: IBM Plex Sans (licencia SIL OFL, en resources/fuentes). */
val LETRA = FontFamily(
    Font(resource = "fuentes/IBMPlexSans-Regular.ttf", weight = FontWeight.Normal),
    Font(resource = "fuentes/IBMPlexSans-Medium.ttf", weight = FontWeight.Medium),
    Font(resource = "fuentes/IBMPlexSans-SemiBold.ttf", weight = FontWeight.SemiBold),
)

/** Letra de ancho fijo, para los mensajes. */
val LETRA_MONO = FontFamily(Font(resource = "fuentes/IBMPlexMono-Regular.ttf", weight = FontWeight.Normal))

private val esquema = darkColorScheme(
    primary = Colores.acento,
    onPrimary = Colores.sobreAcento,
    primaryContainer = Colores.acentoFondo,
    onPrimaryContainer = Colores.acentoTexto,
    secondary = Colores.enlace,
    onSecondary = Colores.sobreAcento,
    secondaryContainer = Colores.fila,
    onSecondaryContainer = Colores.texto,
    tertiary = Colores.enlace,
    onTertiary = Colores.sobreAcento,
    tertiaryContainer = Colores.acentoFondo,
    onTertiaryContainer = Colores.acentoTexto,
    background = Colores.fondo,
    onBackground = Colores.texto,
    surface = Colores.fondo,
    onSurface = Colores.texto,
    surfaceVariant = Colores.tarjetaAlta,
    onSurfaceVariant = Colores.textoSuave,
    surfaceTint = Colores.acento,
    surfaceContainerLowest = Colores.consola,
    surfaceContainerLow = Colores.barra,
    surfaceContainer = Colores.tarjeta,
    surfaceContainerHigh = Colores.tarjetaAlta,
    surfaceContainerHighest = Colores.fila,
    outline = Colores.bordeCampo,
    outlineVariant = Colores.bordeSuave,
    error = Colores.rojo,
    onError = Color(0xFF1A0A08),
    errorContainer = Colores.rojoFondo,
    onErrorContainer = Colores.rojoTexto,
)

private fun conLetra(t: Typography): Typography {
    fun TextStyle.l() = copy(fontFamily = LETRA)
    return Typography(
        displayLarge = t.displayLarge.l(), displayMedium = t.displayMedium.l(), displaySmall = t.displaySmall.l(),
        headlineLarge = t.headlineLarge.l(), headlineMedium = t.headlineMedium.l(),
        headlineSmall = t.headlineSmall.l().copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.l(),
        titleMedium = t.titleMedium.l().copy(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.l().copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.l(),
        bodyMedium = t.bodyMedium.l().copy(fontSize = 14.sp, lineHeight = 21.sp),
        bodySmall = t.bodySmall.l().copy(fontSize = 12.5.sp, lineHeight = 18.sp),
        labelLarge = t.labelLarge.l().copy(fontSize = 14.sp),
        labelMedium = t.labelMedium.l(), labelSmall = t.labelSmall.l(),
    )
}

/** El tema de la app: colores oscuros y letra IBM Plex Sans. También vale para las ventanas. */
@Composable
fun TemaApp(contenido: @Composable () -> Unit) {
    MaterialTheme(colorScheme = esquema, typography = conLetra(Typography()), content = contenido)
}

// ------------------------------------------------------------ botones

private val FORMA_BOTON = RoundedCornerShape(8.dp)
private val RELLENO_BOTON = PaddingValues(horizontal = 18.dp)

/** El botón de la acción principal (azul). */
@Composable
fun BotonPrincipal(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = FORMA_BOTON,
        contentPadding = RELLENO_BOTON,
        colors = ButtonDefaults.buttonColors(
            containerColor = Colores.acento,
            contentColor = Colores.sobreAcento,
            disabledContainerColor = Colores.acentoApagado,
            disabledContentColor = Colores.textoApagado,
        ),
    ) { Text(texto, fontWeight = FontWeight.SemiBold) }
}

/** Un botón con borde, para lo secundario. */
@Composable
fun BotonSecundario(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = FORMA_BOTON,
        contentPadding = RELLENO_BOTON,
        border = BorderStroke(1.dp, if (enabled) Colores.bordeCampo else Colores.borde),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Colores.texto, disabledContentColor = Colores.textoApagado),
    ) { Text(texto) }
}

/** Un botón azul suave (por ejemplo "+ Agregar modelo"). */
@Composable
fun BotonSuave(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = FORMA_BOTON,
        contentPadding = RELLENO_BOTON,
        colors = ButtonDefaults.buttonColors(
            containerColor = Colores.acentoFondo,
            contentColor = Colores.acentoTexto,
            disabledContainerColor = Colores.tarjetaAlta,
            disabledContentColor = Colores.textoApagado,
        ),
    ) { Text(texto, fontWeight = FontWeight.Medium) }
}

/** El botón Parar (rojo). */
@Composable
fun BotonParar(texto: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(40.dp),
        enabled = enabled,
        shape = FORMA_BOTON,
        contentPadding = RELLENO_BOTON,
        border = BorderStroke(1.dp, if (enabled) Colores.rojo else Colores.borde),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Colores.rojo, disabledContentColor = Colores.textoApagado),
    ) { Text(texto, fontWeight = FontWeight.SemiBold) }
}

/** Un botón que parece un enlace. [peligro]: en rojo (Eliminar). */
@Composable
fun BotonEnlace(texto: String, onClick: () -> Unit, enabled: Boolean = true, peligro: Boolean = false) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = FORMA_BOTON,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = if (peligro) Colores.rojo else Colores.enlace,
            disabledContentColor = Colores.textoApagado,
        ),
    ) { Text(texto, style = MaterialTheme.typography.bodyMedium) }
}

// ------------------------------------------------------------ tarjetas y textos

private val FORMA_TARJETA = RoundedCornerShape(12.dp)

/** Un recuadro con el contenido en columna. Ocupa todo el ancho que le dejen. */
@Composable
fun Tarjeta(
    modifier: Modifier = Modifier,
    relleno: Dp = 16.dp,
    espacio: Dp = 10.dp,
    contenido: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.fillMaxWidth()
            .clip(FORMA_TARJETA)
            .background(Colores.tarjeta)
            .border(1.dp, Colores.borde, FORMA_TARJETA)
            .padding(relleno),
        verticalArrangement = Arrangement.spacedBy(espacio),
        content = contenido,
    )
}

/** El título y la explicación de cada pantalla. */
@Composable
fun Cabecera(titulo: String, explicacion: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(titulo, style = MaterialTheme.typography.headlineSmall)
        Text(explicacion, style = MaterialTheme.typography.bodyMedium, color = Colores.textoSuave)
    }
}

/** Un rótulo pequeño en mayúsculas ("EN MARCHA"). */
@Composable
fun Rotulo(texto: String) {
    Text(
        texto.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = Colores.textoSuave,
        letterSpacing = 0.8.sp,
    )
}

/** Una etiqueta redondeada con color ("Lista para subir", "Sesión iniciada…"). [punto]: un círculo delante. */
@Composable
fun Etiqueta(texto: String, fondo: Color, color: Color, punto: Color? = null) {
    Row(
        Modifier.clip(CircleShape).background(fondo).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (punto != null) Box(Modifier.size(8.dp).clip(CircleShape).background(punto))
        Text(texto, style = MaterialTheme.typography.bodySmall, color = color)
    }
}

/** Una barra de progreso que se llena hasta [fraccion] (de 0 a 1). */
@Composable
fun Barra(fraccion: Float, modifier: Modifier = Modifier, alto: Dp = 8.dp, color: Color = Colores.acento) {
    Box(modifier.height(alto).clip(CircleShape).background(Colores.borde)) {
        Box(Modifier.fillMaxWidth(fraccion.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

/** Un campo de texto pequeño para números (Últimos modelos, Máximo de descargas). */
@Composable
fun CampoPequeno(
    valor: String,
    alCambiar: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    ancho: Dp = 64.dp,
) {
    BasicTextField(
        value = valor,
        onValueChange = alCambiar,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = if (enabled) Colores.texto else Colores.textoApagado),
        cursorBrush = SolidColor(Colores.acento),
        modifier = modifier.width(ancho).height(36.dp),
        decorationBox = { campo ->
            Box(
                Modifier.fillMaxWidth().height(36.dp)
                    .clip(FORMA_BOTON)
                    .background(Colores.fondo)
                    .border(1.dp, if (enabled) Colores.bordeCampo else Colores.borde, FORMA_BOTON)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) { campo() }
        },
    )
}
