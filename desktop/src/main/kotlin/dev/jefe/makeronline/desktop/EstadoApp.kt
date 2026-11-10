package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.Rutas
import java.nio.file.Files

/** Las pantallas del menú lateral. */
enum class Pantalla(val titulo: String) {
    SESION(Textos.MENU_SESION),
    SUBIR(Textos.MENU_SUBIR),
    DESCARGAR(Textos.MENU_DESCARGAR),
}

/**
 * El estado de toda la app: la pantalla elegida y el de cada pantalla.
 * Se crea una vez al arrancar.
 */
class EstadoApp(val rutas: Rutas) {
    val hilo = HiloNavegador()
    /** Qué está haciendo Chrome; lo comparten todas las pantallas. */
    val ocupacion = Ocupacion()
    val sesion = EstadoSesion(rutas, hilo, ocupacion)
    /** Lo que se recuerda de una vez para otra (ajustes.properties). */
    val ajustes = Ajustes(rutas.ajustes)
    val subida = EstadoSubida(rutas, hilo, ocupacion, ajustes, sesion)
    val descarga = EstadoDescarga(rutas, hilo, ocupacion, ajustes)
    /** La ventana de añadir y quitar usuarios (botón "Editar" de Descargar). */
    val edicion = EstadoEdicion(rutas, hilo, ocupacion, descarga)
    /** La tarjeta "Conectar con Claude" (pantalla Sesión). */
    val claude = EstadoClaude()
    /** Traer los datos de antes (pantalla Sesión). Después, todo se vuelve a leer. */
    val datos = EstadoDatos(rutas, hilo, ocupacion) { sesionTraida ->
        ajustes.recargar()
        descarga.cargarAjustes()
        subida.cargarAjustes()
        descarga.cargarLista()
        subida.cargar()
        if (sesionTraida) sesion.comprobar()
    }

    var pantalla by mutableStateOf(Pantalla.SESION)

    /** Al cerrar la ventana: para lo que esté en marcha, cierra Chrome y para el hilo del navegador. */
    fun cerrar() {
        subida.alCerrarApp()
        descarga.alCerrarApp()
        sesion.alCerrarApp()
        hilo.close()
    }

    companion object {
        /** Las rutas de la app, dentro de su carpeta de datos (ver [CarpetaDatos]), que se crea si no existe. */
        fun rutas(): Rutas {
            val datos = CarpetaDatos.elegida()
            try {
                Files.createDirectories(datos)
            } catch (_: Exception) {
                // si no se puede crear, cada pantalla dará su error al intentar usarla
            }
            return Rutas(datos)
        }
    }
}
