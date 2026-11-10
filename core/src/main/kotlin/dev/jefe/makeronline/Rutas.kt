package dev.jefe.makeronline

import java.nio.file.Path

/**
 * Dónde guarda el programa cada cosa.
 *
 * Los programas de terminal usan la carpeta desde la que se lanzan, como siempre
 * ([carpetaActual]). La app de escritorio descarga en [descargaEnCurso] y lo deja en un zip
 * en el escritorio; más adelante usará la carpeta de datos del usuario (fase 4).
 */
class Rutas @JvmOverloads constructor(
    /** Carpeta base: sesión de Chrome, candado, usuarios.json y descargados.txt. */
    val datos: Path,
    /** Donde se guardan los modelos descargados: <descargas>/<usuario>/<título>_<id>/. */
    val descargas: Path = datos.resolve("modelos-descargados"),
    /** Carpeta de subidas, con pendientes/, subidos/ y errores/ dentro. */
    val subidas: Path = datos.resolve("subidas"),
) {
    /** Perfil de Chrome con la sesión de MakerOnline. Nunca sale de este ordenador. */
    val perfilChrome: Path get() = datos.resolve("chrome-profile")

    /** Candado que impide que dos programas usen Chrome a la vez (ver AgentLock). */
    val candado: Path get() = datos.resolve(".agente.lock")

    /** Lista de usuarios del Downloader. */
    val usuarios: Path get() = datos.resolve("usuarios.json")

    /** Lista de usuarios de Creality Cloud de los que se descarga (aparte de la de MakerOnline). */
    val usuariosCreality: Path get() = datos.resolve("usuarios-creality.json")

    /** Como [registro], pero de Creality Cloud: un id de modelo por línea. */
    val registroCreality: Path get() = datos.resolve("descargados-creality.txt")

    /** Ajustes de la app de escritorio que se recuerdan de una vez para otra. */
    val ajustes: Path get() = datos.resolve("ajustes.properties")

    /**
     * Un id de modelo por línea. Segunda protección además del like: aunque el like
     * falle o se vacíe la carpeta de descargas, lo que está aquí no se vuelve a descargar.
     * Está fuera de la carpeta de descargas para que no se pierda al vaciarla.
     * Para volver a descargar un modelo, se borra su línea.
     */
    val registro: Path get() = datos.resolve("descargados.txt")

    /** Donde estaba antes el registro: si existe, se pasa a [registro]. */
    val registroAntiguo: Path get() = datos.resolve("modelos-descargados").resolve("descargados.txt")

    /**
     * La app descarga aquí y, al terminar, lo pasa a un zip en el escritorio y vacía la carpeta
     * (ver ZipDescargas en la app). La terminal sigue usando [descargas].
     */
    val descargaEnCurso: Path get() = datos.resolve("descarga-en-curso")

    /** Errores de las descargas de la app, por usuario: errores.txt y capturas error_<id>.png. */
    val erroresDescargas: Path get() = datos.resolve("errores-descargas")

    /** Las mismas rutas, pero con las descargas en [descargaEnCurso]: las que usa la app. */
    fun paraLaApp(): Rutas = Rutas(datos, descargaEnCurso, subidas)

    /** Modelos preparados para subir. */
    val pendientes: Path get() = subidas.resolve("pendientes")

    /** Modelos ya subidos como borrador. */
    val subidos: Path get() = subidas.resolve("subidos")

    /** Modelos que no se han podido subir, con error.txt y una captura. */
    val errores: Path get() = subidas.resolve("errores")

    /** Carpetas eliminadas desde la app cuando no se pueden mandar a la papelera de Windows. */
    val eliminados: Path get() = subidas.resolve("eliminados")

    companion object {
        /** La carpeta desde la que se lanza el programa: lo de siempre en la versión de terminal. */
        @JvmStatic
        fun carpetaActual(): Rutas = Rutas(Path.of(""))
    }
}
