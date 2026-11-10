package dev.jefe.makeronline

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Los modelos que faltan por subir a MakerWorld, para la extensión de Chrome (ver ServidorMakerWorld y
 * la carpeta extension-makerworld/).
 *
 * MakerWorld no deja entrar al Chrome que maneja la app (Cloudflare lo para; comprobado el 10/11/2026,
 * ver docs/contexto-makerworld.md), así que a MakerWorld se sube a mano en el Chrome de siempre y la
 * extensión rellena el formulario con lo que le da esta cola. La app no sube nada a MakerWorld.
 *
 * Están en la cola las carpetas válidas de pendientes/ y también de subidos/ que no tienen
 * `subido-makerworld.txt`: así MakerOnline no tiene que esperar a MakerWorld (al subir a MakerOnline la
 * carpeta pasa a subidos/, y desde ahí se sigue pudiendo subir a MakerWorld cuando quieras).
 *
 * Cada carpeta se nombra por su "clave": "pendientes/<nombre>" o "subidos/<nombre>".
 */
class ColaMakerWorld(private val rutas: Rutas) {

    /** Un archivo de la ficha: el nombre en la carpeta y para qué es. */
    data class Archivo(val nombre: String, val tipo: Tipo)

    enum class Tipo {
        /** .stl o .3mf: van a "Raw Model Files". */
        MODELO,
        /** La portada: va a las dos portadas (4:3 y 3:4). */
        PORTADA,
        /** Las demás imágenes: van a "Model Pictures". */
        FOTO,
    }

    /**
     * Lo que la extensión escribe en el formulario de un modelo, ya adaptado a MakerWorld (nombre
     * recortado, categoría y licencia de MakerWorld, etiquetas limpias). Los límites y valores son los
     * de CatalogoMakerWorld (comprobados el 10/11/2026).
     */
    data class Ficha(
        val clave: String,
        val titulo: String,
        val descripcion: String,
        val etiquetas: List<String>,
        val categoria: CatalogoMakerWorld.Eleccion,
        val licencia: String,
        val archivos: List<Archivo>,
        /** Lo que se ha cambiado para MakerWorld (nombre cortado, licencia que no existe…), para enseñarlo. */
        val avisos: List<String>,
    )

    /** Cómo está la cola. */
    data class Estado(val pendientes: List<String>, val conProblemas: List<String>, val subidas: Int)

    /** Las claves de las carpetas que faltan, por orden (primero pendientes/, luego subidos/), y las que no son válidas. */
    fun estado(): Estado {
        val pendientes = ArrayList<String>()
        val conProblemas = ArrayList<String>()
        var subidas = 0
        for ((donde, dir) in carpetas()) {
            val clave = "$donde/${dir.fileName}"
            if (yaSubido(dir)) {
                subidas++
                continue
            }
            try {
                ModelFolder.load(dir)
                pendientes += clave
            } catch (_: Exception) {
                // en subidos/ solo hay carpetas que ya pasaron la validación; en pendientes/ se avisa
                if (donde == PENDIENTES) conProblemas += clave
            }
        }
        return Estado(pendientes, conProblemas, subidas)
    }

    /** La ficha de la primera carpeta que falta y no está en [saltar], o null si no queda ninguna. */
    fun siguiente(saltar: Set<String> = emptySet()): Ficha? =
        estado().pendientes.firstOrNull { it !in saltar }?.let { ficha(it) }

    /** La ficha de una carpeta por su clave. */
    fun ficha(clave: String): Ficha {
        val m = ModelFolder.load(carpeta(clave))
        val meta = m.meta()
        val avisos = ArrayList<String>()

        var titulo = meta.title().trim()
        if (titulo.length > CatalogoMakerWorld.MAX_NOMBRE) {
            titulo = titulo.take(CatalogoMakerWorld.MAX_NOMBRE).trim()
            avisos += "El nombre tiene más de ${CatalogoMakerWorld.MAX_NOMBRE} caracteres: se ha cortado."
        }

        val licencia = CatalogoMakerWorld.licenciaDeMakerOnline(meta.licenseOrDefault()) ?: run {
            avisos += "La licencia \"${meta.licenseOrDefault()}\" no existe en MakerWorld: se pone ${CatalogoMakerWorld.LICENCIA_POR_DEFECTO}."
            CatalogoMakerWorld.LICENCIA_POR_DEFECTO
        }

        val max = CatalogoMakerWorld.MAX_LETRAS_ETIQUETA
        val etiquetas = meta.tags().flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.length > max) it.take(max).trim() else it }.distinctBy { it.lowercase() }
        if (etiquetas.size > CatalogoMakerWorld.MAX_ETIQUETAS) {
            avisos += "MakerWorld admite ${CatalogoMakerWorld.MAX_ETIQUETAS} etiquetas: van las primeras."
        }

        val fotos = m.imagenes().drop(1)
        if (fotos.size > CatalogoMakerWorld.MAX_FOTOS) {
            avisos += "MakerWorld admite ${CatalogoMakerWorld.MAX_FOTOS} fotos: van las primeras."
        }
        val archivos = m.modelos().map { Archivo(it.fileName.toString(), Tipo.MODELO) } +
            Archivo(m.portada().fileName.toString(), Tipo.PORTADA) +
            fotos.take(CatalogoMakerWorld.MAX_FOTOS).map { Archivo(it.fileName.toString(), Tipo.FOTO) }

        return Ficha(
            clave = clave,
            titulo = titulo,
            descripcion = meta.description(),
            etiquetas = etiquetas.take(CatalogoMakerWorld.MAX_ETIQUETAS),
            categoria = CatalogoMakerWorld.paraMakerOnline(meta.category()),
            licencia = licencia,
            archivos = archivos,
            avisos = avisos,
        )
    }

    /**
     * Un archivo de una carpeta de la cola, solo si es uno de los de su ficha (modelos o imágenes):
     * así no se puede pedir ningún otro archivo del ordenador.
     */
    fun archivo(clave: String, nombre: String): Path? {
        val dir = carpeta(clave)
        val m = try {
            ModelFolder.load(dir)
        } catch (_: Exception) {
            return null
        }
        return (m.modelos() + m.imagenes()).firstOrNull { it.fileName.toString() == nombre }
    }

    /** Apunta en la carpeta que ya está en MakerWorld (id del borrador, dirección y fecha). */
    fun anotarSubido(clave: String, borrador: String) {
        require(borrador.isNotBlank() && borrador.all { it.isDigit() }) { "id de borrador no válido: $borrador" }
        val dir = carpeta(clave)
        Files.writeString(
            dir.resolve(REGISTRO),
            "$borrador\t${direccionBorrador(borrador)}\t${LocalDateTime.now().format(FECHA)}\n",
        )
    }

    /**
     * La carpeta de una clave ("pendientes/x" o "subidos/x"). Solo carpetas que están directamente
     * dentro de pendientes/ o subidos/ (nada de "..").
     */
    fun carpeta(clave: String): Path {
        val donde = clave.substringBefore('/')
        val nombre = clave.substringAfter('/', "")
        val base = when (donde) {
            PENDIENTES -> rutas.pendientes
            SUBIDOS -> rutas.subidos
            else -> throw IllegalArgumentException("clave no válida: $clave")
        }
        if (nombre.isBlank() || nombre == "." || nombre == ".." || '/' in nombre || '\\' in nombre) {
            throw IllegalArgumentException("clave no válida: $clave")
        }
        val dir = base.resolve(nombre)
        if (!Files.isDirectory(dir)) throw IllegalArgumentException("no existe la carpeta $clave")
        return dir
    }

    /** Las carpetas de pendientes/ y de subidos/, con dónde está cada una, por nombre. */
    private fun carpetas(): List<Pair<String, Path>> {
        fun de(donde: String, base: Path): List<Pair<String, Path>> = try {
            if (!Files.isDirectory(base)) emptyList()
            else Files.list(base).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }.map { donde to it }
        } catch (_: IOException) {
            emptyList()
        }
        return de(PENDIENTES, rutas.pendientes) + de(SUBIDOS, rutas.subidos)
    }

    companion object {
        const val WEB = "https://makerworld.com"
        /** El formulario de subida (comprobado el 10/11/2026). */
        const val FORMULARIO = "$WEB/en/my/models/publish"
        /**
         * El formulario en "modo seguido": la extensión rellena cada modelo sola y, tras guardar, pasa al
         * siguiente (la marca va tras "#", así MakerWorld ni la ve).
         */
        const val FORMULARIO_SEGUIDO = "$FORMULARIO#jujamaker-auto"

        /** Dentro de la carpeta del modelo: lo subido a MakerWorld (id del borrador, dirección y fecha). */
        const val REGISTRO = "subido-makerworld.txt"

        private const val PENDIENTES = "pendientes"
        private const val SUBIDOS = "subidos"
        private val FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** Si esa carpeta ya se subió a MakerWorld. */
        @JvmStatic
        fun yaSubido(carpeta: Path): Boolean = Files.exists(carpeta.resolve(REGISTRO))

        /** Dónde se edita un borrador en MakerWorld. */
        @JvmStatic
        fun direccionBorrador(id: String): String = "$WEB/en/my/models/drafts/$id/edit"
    }
}
