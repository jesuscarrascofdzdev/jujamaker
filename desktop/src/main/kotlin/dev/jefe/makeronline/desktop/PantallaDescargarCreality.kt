package dev.jefe.makeronline.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import dev.jefe.makeronline.ListaUsuariosCreality
import dev.jefe.makeronline.Web

/**
 * La pestaña Creality Cloud de "Usuarios" en la pantalla Descargar: su lista
 * (usuarios-creality.json). Aquí mismo se añaden (pegando la dirección del perfil) y se quitan.
 */
@Composable
internal fun UsuariosCreality(d: EstadoDescargas) {
    val c = d.creality
    val editable = !d.enMarcha
    val puedeAgregar = editable && c.errorLista == null && c.texto.isNotBlank()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(Textos.usuariosTitulo(c.usuarios.size), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        BotonEnlace(Textos.RECARGAR, { c.cargarLista() }, enabled = editable)
    }
    c.errorLista?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    // Añadir: se pega la dirección del perfil (lleva el número del usuario)
    Text(Textos.CREALITY_CAMPO_AGREGAR, style = MaterialTheme.typography.bodySmall, color = Colores.textoClaro)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CampoPequeno(
            c.texto,
            { c.texto = it.take(300) },
            enabled = editable && c.errorLista == null,
            modifier = Modifier.weight(1f).onPreviewKeyEvent {
                // ENTER en el campo hace lo mismo que el botón "Añadir"
                if ((it.key == Key.Enter || it.key == Key.NumPadEnter) && it.type == KeyEventType.KeyDown) {
                    if (puedeAgregar) c.agregar()
                    true
                } else {
                    false
                }
            },
        )
        BotonSecundario(Textos.AGREGAR, { c.agregar() }, enabled = puedeAgregar)
    }
    Text(Textos.CREALITY_AGREGAR_AYUDA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    c.errorEdicion?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

    if (c.usuarios.isEmpty() && c.errorLista == null) {
        Text(Textos.CREALITY_LISTA_VACIA, style = MaterialTheme.typography.bodySmall, color = Colores.textoSuave)
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (entrada in c.usuarios) {
            val id = ListaUsuariosCreality.idDe(entrada)
            FilaUsuario(
                d, Web.CREALITY, entrada,
                ListaUsuariosCreality.nombreDe(entrada) ?: Textos.crealityUsuarioSinNombre(id),
                id?.let { Textos.crealityIdUsuario(it) },
                c.marcado(entrada), { c.cambiarMarca(entrada, it) },
            ) {
                // Quitar (pregunta antes); mientras se descarga no se puede
                if (c.aQuitar == entrada) {
                    BotonEnlace(Textos.SI_QUITAR, { c.quitar(entrada) }, enabled = editable, peligro = true)
                    BotonEnlace(Textos.NO, { c.aQuitar = null })
                } else {
                    BotonEnlace(Textos.QUITAR, { c.aQuitar = entrada }, enabled = editable)
                }
            }
        }
    }
    PieLista(d, { c.marcarTodos() }, { c.desmarcarTodos() }, c.marcados)
}
