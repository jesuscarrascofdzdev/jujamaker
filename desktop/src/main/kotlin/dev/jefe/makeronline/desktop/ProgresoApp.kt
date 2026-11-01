package dev.jefe.makeronline.desktop

import dev.jefe.makeronline.Progreso
import javax.swing.SwingUtilities

/** Tipo de mensaje, con el mismo símbolo que en la terminal. */
enum class TipoMensaje(val simbolo: String) {
    INFO(""), PASO("→"), CORRECTO("✓"), SALTADO("↷"), LIKE("♥"), NOTA("·"), ERROR("✘"), AVISO("⚠"),
}

/** Lo que cuenta core mientras trabaja, convertido en datos para la ventana. */
sealed interface Evento {
    data class Mensaje(val tipo: TipoMensaje, val texto: String) : Evento
    data class Usuario(val indice: Int, val total: Int, val nombre: String) : Evento
    data class Modelo(val indice: Int, val total: Int, val titulo: String) : Evento
    data class Paginas(val pagina: Int, val ultima: Int) : Evento
    data class Pausa(val segundos: Long) : Evento
    data class Captcha(val para: String, val minutos: Long) : Evento
    data object CaptchaTerminado : Evento
}

/**
 * [Progreso] para la app. core lo llama desde el hilo del navegador; aquí cada aviso se
 * convierte en un [Evento] y se entrega en el hilo de la ventana (que es el único que
 * puede cambiar lo que se ve), en el mismo orden en que llegaron.
 */
class ProgresoApp(private val alEvento: (Evento) -> Unit) : Progreso {

    private fun enviar(e: Evento) = SwingUtilities.invokeLater { alEvento(e) }

    override fun info(texto: String) = enviar(Evento.Mensaje(TipoMensaje.INFO, texto))
    override fun paso(texto: String) = enviar(Evento.Mensaje(TipoMensaje.PASO, texto))
    override fun correcto(texto: String) = enviar(Evento.Mensaje(TipoMensaje.CORRECTO, texto))
    override fun saltado(texto: String) = enviar(Evento.Mensaje(TipoMensaje.SALTADO, texto))
    override fun like(texto: String) = enviar(Evento.Mensaje(TipoMensaje.LIKE, texto))
    override fun nota(texto: String) = enviar(Evento.Mensaje(TipoMensaje.NOTA, texto))
    override fun error(texto: String) = enviar(Evento.Mensaje(TipoMensaje.ERROR, texto))
    override fun usuario(indice: Int, total: Int, nombre: String) = enviar(Evento.Usuario(indice, total, nombre))
    override fun modelo(indice: Int, total: Int, titulo: String) = enviar(Evento.Modelo(indice, total, titulo))
    override fun leyendoPaginas(pagina: Int, ultima: Int) = enviar(Evento.Paginas(pagina, ultima))
    override fun pausa(segundos: Long) = enviar(Evento.Pausa(segundos))
    override fun captcha(para: String, minutos: Long) = enviar(Evento.Captcha(para, minutos))
    override fun captchaTerminado() = enviar(Evento.CaptchaTerminado)
}
