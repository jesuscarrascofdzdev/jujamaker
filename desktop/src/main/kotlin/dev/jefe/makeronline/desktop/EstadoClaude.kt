package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Path

/** La tarjeta "Conectar con Claude" de la pantalla Sesión (ver [ConectorClaude]). */
class EstadoClaude {
    var estado by mutableStateOf(ConectorClaude.Estado.NO_CONECTADO)
        private set
    /** El .exe de la app instalada que arrancará Claude, o null si no hay (desde IntelliJ sin instalar). */
    var exe by mutableStateOf<Path?>(null)
        private set
    /** Lo último que ha salido bien, o null. */
    var aviso by mutableStateOf<String?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** El archivo de configuración de Claude que se cambia (se enseña en la tarjeta). */
    var archivo by mutableStateOf<Path>(ConectorClaude.archivoConfig())
        private set

    init {
        refrescar()
    }

    /** Vuelve a mirar el archivo de Claude (por si se ha cambiado a mano). */
    fun refrescar() {
        archivo = ConectorClaude.archivoConfig()
        exe = ConectorClaude.ejecutable()
        estado = try {
            ConectorClaude.estado(exe = exe)
        } catch (_: Exception) {
            ConectorClaude.Estado.NO_CONECTADO
        }
    }

    fun conectar() {
        val e = exe ?: return
        hacer(Textos.CLAUDE_CONECTADO_REINICIA) { ConectorClaude.conectar(e) }
    }

    fun quitar() {
        hacer(Textos.CLAUDE_QUITADO_REINICIA) { ConectorClaude.quitar() }
    }

    fun copiarComando() {
        val e = exe ?: return
        aviso = if (Portapapeles.copiarTexto(ConectorClaude.comandoClaudeCode(e))) Textos.CLAUDE_COMANDO_COPIADO else null
        error = if (aviso == null) Textos.NO_SE_PUDO_COPIAR else null
    }

    private fun hacer(siSale: String, accion: () -> Unit) {
        aviso = null
        error = null
        try {
            accion()
            aviso = siSale
        } catch (e: IllegalStateException) {
            error = e.message
        } catch (e: Exception) {
            error = Textos.errorInesperado(e)
        } finally {
            refrescar()
        }
    }
}
