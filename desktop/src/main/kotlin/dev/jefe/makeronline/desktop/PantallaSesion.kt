package dev.jefe.makeronline.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun PantallaSesion(s: EstadoSesion, d: EstadoDatos, alcance: CoroutineScope) {
    // La primera vez que se abre la pantalla, se comprueba la sesión
    LaunchedEffect(Unit) {
        if (s.estado == null) alcance.launch { s.comprobar() }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(Textos.SESION_TITULO, style = MaterialTheme.typography.headlineSmall)
        Text(Textos.SESION_EXPLICACION, style = MaterialTheme.typography.bodyMedium)

        val e = s.estado

        // --- Traer los datos de antes (solo si los hay y la app todavía no tiene nada) ---
        d.sugerida?.let { anterior ->
            Tarjeta {
                Text(Textos.TRAER_TITULO, style = MaterialTheme.typography.titleMedium)
                Text(Textos.traerExplicacion(anterior.toString()), style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = d.libre, onClick = { alcance.launch { d.traer(anterior) } }) {
                        Text(Textos.TRAER)
                    }
                    OutlinedButton(enabled = d.libre, onClick = { traerDeOtra(d, alcance) }) {
                        Text(Textos.TRAER_DE_OTRA)
                    }
                }
            }
        }
        if (d.trayendo) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(Textos.TRAYENDO)
        }
        d.resumen?.let { Text(it, color = VERDE) }
        for (aviso in d.avisos) Text("⚠ $aviso", color = MaterialTheme.colorScheme.error)
        d.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // --- Chrome ---
        Tarjeta {
            Text(Textos.CHROME, style = MaterialTheme.typography.titleMedium)
            when {
                e == null -> Text(if (s.ocupado) Textos.COMPROBANDO else Textos.SIN_COMPROBAR)
                e.chromeInstalado -> Text("✔ " + Textos.CHROME_OK, color = VERDE)
                else -> {
                    Text("✘ " + Textos.CHROME_FALTA, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { abrirEnNavegador(Textos.URL_CHROME) }) { Text(Textos.DESCARGAR_CHROME) }
                }
            }
        }

        // --- MakerOnline ---
        Tarjeta {
            Text(Textos.MAKERONLINE, style = MaterialTheme.typography.titleMedium)
            when {
                e == null -> Text(if (s.ocupado) Textos.COMPROBANDO else Textos.SIN_COMPROBAR)
                e.iniciada -> Text(
                    "✔ " + (e.nombre?.let { Textos.sesionIniciadaComo(it) } ?: Textos.SESION_INICIADA),
                    color = VERDE,
                )
                else -> Text("✘ " + Textos.sinSesion(e.motivo), color = MaterialTheme.colorScheme.error)
            }
        }

        // --- Acciones ---
        if (s.esperandoLogin) {
            Tarjeta {
                Text(Textos.INSTRUCCIONES_LOGIN, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !s.ocupado, onClick = { alcance.launch { s.yaHeEntrado() } }) {
                        Text(Textos.YA_HE_ENTRADO)
                    }
                    OutlinedButton(enabled = !s.ocupado, onClick = { alcance.launch { s.cancelarInicio() } }) {
                        Text(Textos.CANCELAR)
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = s.libre && e?.chromeInstalado != false,
                    onClick = { alcance.launch { s.abrirParaIniciar() } },
                ) {
                    Text(if (e?.iniciada == true) Textos.VOLVER_A_INICIAR else Textos.INICIAR_SESION)
                }
                OutlinedButton(enabled = s.libre, onClick = { alcance.launch { s.comprobar() } }) {
                    Text(Textos.COMPROBAR)
                }
            }
        }

        if (s.ocupado) LinearProgressIndicator(Modifier.fillMaxWidth())
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        // --- Dónde guarda la app sus datos ---
        Text(
            Textos.carpetaDatos(d.carpeta.toString()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { abrir(d.carpeta) }) { Text(Textos.ABRIR_CARPETA_DATOS) }
            if (d.sugerida == null) {
                TextButton(enabled = d.libre, onClick = { traerDeOtra(d, alcance) }) { Text(Textos.TRAER_DE_OTRA) }
            }
        }
    }
}

/** Elige la carpeta donde estaban los datos (la que tiene chrome-profile y usuarios.json) y los trae. */
private fun traerDeOtra(d: EstadoDatos, alcance: CoroutineScope) {
    val elegida = elegirCarpetas(Textos.ELEGIR_CARPETA_ANTERIOR).firstOrNull() ?: return
    alcance.launch { d.traer(elegida) }
}

