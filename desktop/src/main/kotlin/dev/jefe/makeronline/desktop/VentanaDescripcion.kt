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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import dev.jefe.makeronline.CatalogoMakerOnline

/**
 * Ventana Descripción: el formulario con los textos de un modelo. Se puede guardar aunque
 * falte algo; abajo dice lo que falta para poder subirlo.
 *
 * @param titulo  el título de la ventana (p. ej. "Descripción de llavero-gato")
 * @param aviso   algo a tener en cuenta al abrir (p. ej. que el archivo actual no se podía leer)
 * @param error   por qué no se ha podido guardar
 */
@Composable
fun VentanaDescripcion(
    f: FormularioTextos,
    titulo: String,
    aviso: String?,
    error: String?,
    alGuardar: () -> Unit,
    alCerrar: () -> Unit,
) {
    DialogWindow(
        onCloseRequest = alCerrar,
        state = rememberDialogState(width = 760.dp, height = 860.dp),
        title = titulo,
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(titulo, style = MaterialTheme.typography.headlineSmall)
                    aviso?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                    OutlinedTextField(
                        value = f.titulo,
                        onValueChange = { f.titulo = it },
                        label = { Text(Textos.CAMPO_TITULO) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = f.etiquetas,
                        onValueChange = { f.etiquetas = it },
                        label = { Text(Textos.CAMPO_ETIQUETAS) },
                        supportingText = { Text(Textos.ETIQUETAS_AYUDA) },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Desplegable(Textos.CAMPO_LICENCIA, f.licencia, f.licencias(), Modifier.fillMaxWidth()) {
                        f.licencia = it
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Desplegable(Textos.CAMPO_CATEGORIA, f.categoria, f.categorias(), Modifier.weight(1f)) {
                            f.elegirCategoria(it)
                        }
                        Desplegable(
                            Textos.CAMPO_SUBCATEGORIA, f.subcategoria, f.subcategorias(), Modifier.weight(1f),
                            activo = f.categoria != null,
                        ) { f.subcategoria = it }
                    }
                    Text(
                        f.categoriaElegida?.let { Textos.categoriaElegida(it) } ?: Textos.SIN_CATEGORIA,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (CatalogoMakerOnline.CATEGORIAS.isEmpty()) {
                        Text(
                            Textos.FALTAN_OPCIONES,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text(Textos.CAMPO_TIPO, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        for (t in f.tipos()) Eleccion(t, f.tipo == t, true) { f.tipo = t }
                    }

                    OutlinedTextField(
                        value = f.descripcion,
                        onValueChange = { f.descripcion = it },
                        label = { Text(Textos.CAMPO_DESCRIPCION) },
                        minLines = 8,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Lo que falta (se puede guardar igual) y los botones
                val faltan = f.faltan()
                if (faltan.isEmpty()) {
                    Text(Textos.TEXTOS_COMPLETOS, color = VERDE)
                } else {
                    Text(Textos.faltanTextos(faltan), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = alCerrar) { Text(Textos.CANCELAR) }
                    Button(onClick = alGuardar) { Text(Textos.GUARDAR) }
                }
            }
        }
    }
}
