package dev.jefe.makeronline.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jefe.makeronline.ListaUsuariosPrintables

/**
 * La tarjeta de Printables de la pantalla Descargar: su lista de usuarios, su máximo, sus botones y
 * cómo va. Va aparte del botón común porque descarga la extensión de Chrome, no la app (ver
 * [EstadoDescargaPrintables]). "Últimos modelos" y "todos" son los de las opciones comunes.
 */
@Composable
internal fun PanelDescargasPrintables(p: EstadoDescargaPrintables, d: EstadoDescargas) {
    val editable = !p.enMarcha && !d.enMarcha
    val puedeAgregar = editable && p.errorLista == null && p.texto.isNotBlank()
    val ultimos = if (d.todos) 0 else d.ultimos.toIntOrNull() ?: 0
    val puedeEmpezar = p.libre && (d.todos || ultimos >= 1)

    Tarjeta(espacio = 8.dp) {
        Text(Textos.PRD_TITULO, style = MaterialTheme.typography.titleMedium)
        Text(Textos.PRD_EXPLICACION, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)

        // ---- usuarios
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Textos.usuariosTitulo(p.usuarios.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            BotonEnlace(Textos.RECARGAR, { p.cargarLista() }, enabled = editable)
        }
        p.errorLista?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(Textos.PRD_CAMPO_AGREGAR, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CampoPequeno(
                p.texto,
                { p.texto = it.take(300) },
                enabled = editable && p.errorLista == null,
                modifier = Modifier.weight(1f).onPreviewKeyEvent {
                    // ENTER en el campo hace lo mismo que el botón "Añadir"
                    if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyEventType.KeyDown) {
                        if (puedeAgregar) p.agregar()
                        true
                    } else {
                        false
                    }
                },
            )
            BotonSecundario(Textos.AGREGAR, { p.agregar() }, enabled = puedeAgregar)
        }
        Text(Textos.PRD_AGREGAR_AYUDA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        p.errorEdicion?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (p.usuarios.isEmpty() && p.errorLista == null) {
            Text(Textos.PRD_LISTA_VACIA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for (entrada in p.usuarios) {
                val handle = ListaUsuariosPrintables.handleDe(entrada)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(p.marcado(entrada), { p.cambiarMarca(entrada, it) }, enabled = editable)
                    Column(Modifier.weight(1f)) {
                        Text(
                            ListaUsuariosPrintables.nombreDe(entrada) ?: Textos.prdUsuarioSinNombre(handle),
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        if (handle != null && ListaUsuariosPrintables.nombreDe(entrada) != null) {
                            Text("@$handle", style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
                        }
                    }
                    // Quitar (pregunta antes); mientras se descarga no se puede
                    if (p.aQuitar == entrada) {
                        BotonEnlace(Textos.SI_QUITAR, { p.quitar(entrada) }, enabled = editable, peligro = true)
                        BotonEnlace(Textos.NO, { p.aQuitar = null })
                    } else {
                        BotonEnlace(Textos.QUITAR, { p.aQuitar = entrada }, enabled = editable)
                    }
                }
            }
        }
        if (p.usuarios.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BotonEnlace(Textos.MARCAR_TODOS, { p.marcarTodos() }, enabled = editable)
                BotonEnlace(Textos.DESMARCAR_TODOS, { p.desmarcarTodos() }, enabled = editable)
            }
        }

        // ---- máximo
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Textos.MAXIMO, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
            CampoPequeno(p.maximo, { t -> p.cambiarMaximo(t.filter { it.isDigit() }.take(5)) }, enabled = editable)
            Text(Textos.MAXIMO_AYUDA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
        }

        // ---- botones
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BotonSecundario(Textos.SIMULAR, { p.empezar(simular = true, ultimos = ultimos) }, enabled = puedeEmpezar)
            BotonPrincipal(Textos.PRD_DESCARGAR, { p.empezar(simular = false, ultimos = ultimos) }, enabled = puedeEmpezar)
            BotonParar(Textos.PARAR, { p.parar() }, enabled = p.enMarcha && !p.parando)
        }
        if (!d.todos && ultimos < 1) Text(Textos.ERROR_ULTIMOS, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        p.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        // ---- cómo va
        if (p.enMarcha) {
            if (!p.conectada) {
                Text(Textos.PRD_ESPERANDO, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
                BotonEnlace(Textos.PRD_REABRIR, { p.reabrir() })
            }
            p.usuarioActual?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            p.modeloActual?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        p.resultado?.let { Text(Textos.prdResumen(it), style = MaterialTheme.typography.bodyMedium) }
        p.zip?.let { z ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Textos.zipCreado(z.fileName.toString(), p.modelosEnZip), style = MaterialTheme.typography.bodySmall,
                    color = Colores.verdeTexto, modifier = Modifier.weight(1f))
                BotonEnlace(Textos.ABRIR_CARPETA_DATOS, { abrir(z.parent) })
            }
        }
        p.errorZip?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if ((p.resultado?.errores ?: 0) > 0) BotonEnlace(Textos.VER_ERRORES, { abrir(p.carpetaErrores) })
        if (p.mensajes.isNotEmpty()) ListaMensajes(p.mensajes, Modifier.height(240.dp).padding(top = 4.dp))
    }
}
