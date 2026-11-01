package dev.jefe.makeronline.desktop

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState

/** Ventana "Pegar descripción": el texto que se pega y el botón Rellenar (ver [EdicionPegar]). */
@Composable
fun VentanaPegar(e: EdicionPegar, alRellenar: () -> Unit, alCerrar: () -> Unit) {
    DialogWindow(
        onCloseRequest = alCerrar,
        state = rememberDialogState(width = 760.dp, height = 760.dp),
        title = Textos.tituloPegar(e.carpeta),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(Textos.tituloPegar(e.carpeta), style = MaterialTheme.typography.headlineSmall)
                    Text(Textos.PEGAR_EXPLICACION, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = e.texto,
                        onValueChange = { e.texto = it },
                        label = { Text(Textos.PEGAR_CAMPO) },
                        minLines = 14,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(onClick = {
                        val copiado = Portapapeles.leerTexto()
                        if (copiado.isNullOrBlank()) {
                            e.error = Textos.PEGAR_NADA
                        } else {
                            e.texto = copiado
                            e.error = null
                        }
                    }) { Text(Textos.PEGAR_DEL_PORTAPAPELES) }
                }
                e.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = alCerrar) { Text(Textos.CANCELAR) }
                    Button(enabled = e.texto.isNotBlank(), onClick = alRellenar) { Text(Textos.PEGAR_RELLENAR) }
                }
            }
        }
    }
}
