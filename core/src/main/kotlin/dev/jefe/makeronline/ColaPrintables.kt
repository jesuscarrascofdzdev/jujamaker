package dev.jefe.makeronline

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.Locale

/**
 * Los modelos que faltan por subir a Printables, para la extensión de Chrome (ver ServidorPrintables y
 * la carpeta extension-printables/).
 *
 * Printables no deja entrar al Chrome que maneja la app (Cloudflare se queda en "Just a moment..." en
 * bucle; comprobado el 10/11/2026 con DiagnosticoPrintables, ver docs/contexto-printables.md), así que a
 * Printables se sube a mano en el Chrome de siempre y la extensión rellena el formulario con lo que le
 * da esta cola. La app no sube nada a Printables.
 *
 * Están en la cola las carpetas válidas de pendientes/ y también de subidos/ que no tienen
 * `subido-printables.txt`: así MakerOnline no tiene que esperar a Printables (al subir a MakerOnline la
 * carpeta pasa a subidos/, y desde ahí se sigue pudiendo subir a Printables cuando quieras). Ese archivo
 * importa: las condiciones de Printables prohíben subir dos veces el mismo modelo (cl. 3.2).
 *
 * Cada carpeta se nombra por su "clave": "pendientes/<nombre>" o "subidos/<nombre>".
 */
class ColaPrintables(private val rutas: Rutas) {

    /** Un archivo de la ficha: el nombre en la carpeta y para qué es. */
    data class Archivo(val nombre: String, val tipo: Tipo)

    enum class Tipo {
        /** .stl o .3mf: Printables los pone solo en "Model Files". */
        MODELO,
        /** La portada: la primera de "Photos" (la que sale de portada del modelo). */
        PORTADA,
        /** Las demás imágenes: detrás, en "Photos". */
        FOTO,
    }

    /**
     * Lo que la extensión escribe en el formulario de un modelo, ya adaptado a Printables (resumen,
     * categoría y licencia de Printables, etiquetas de una palabra). Los valores son los de
     * CatalogoPrintables (comprobados el 10/11/2026).
     */
    data class Ficha(
        val clave: String,
        val titulo: String,
        /** "Summary (required)", de [CatalogoPrintables.MAX_RESUMEN] caracteres como mucho. */
        val resumen: String,
        val descripcion: String,
        val etiquetas: List<String>,
        val categoria: CatalogoPrintables.Categoria,
        /** El texto exacto de la opción del desplegable "License". */
        val licencia: String,
        /** Primero la portada y las fotos, luego los modelos: así la portada es la primera foto. */
        val archivos: List<Archivo>,
        /** Lo que se ha cambiado para Printables (resumen cortado, licencia que no existe…), para enseñarlo. */
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

        val descripcion = meta.description().trim()
        val resumen = resumenDe(descripcion)
        if (resumen.endsWith("…")) {
            avisos += "Resumen (\"Summary\"): el principio de la descripción, cortado a ${CatalogoPrintables.MAX_RESUMEN} caracteres."
        }

        val licencia = CatalogoPrintables.licenciaDeMakerOnline(meta.licenseOrDefault()) ?: run {
            avisos += "La licencia \"${meta.licenseOrDefault()}\" no existe en Printables: se pone ${CatalogoPrintables.LICENCIA_POR_DEFECTO}."
            CatalogoPrintables.LICENCIA_POR_DEFECTO
        }

        // Printables separa las etiquetas por espacios ("Use space to separate tags": "dos palabras" entra
        // como "dos" y "palabras") y solo admite minúsculas sin tilde y números: "scale-model", "ABC",
        // "año", "a_b" o "x.y" no entran (comprobado el 10/11/2026). Por eso se dejan ya así aquí.
        val originales = meta.tags().flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
        val etiquetas = originales.flatMap { it.split(Regex("\\s+")) }.map { etiquetaPrintables(it) }
            .filter { it.isNotEmpty() }.distinct()
        if (originales.any { o -> o.split(Regex("\\s+")).any { etiquetaPrintables(it) != it } }) {
            avisos += "Printables solo admite etiquetas de una palabra en minúsculas, sin tildes ni símbolos: se han adaptado."
        }

        // Dos archivos iguales byte a byte ("modelo.3mf" y "modelo (1).3mf") saldrían dos veces en "Model
        // Files": va solo el primero, y se avisa. Printables no quiere subidas repetidas (cl. 3.2).
        val modelos = sinRepetidos(m.modelos())
        if (modelos.size < m.modelos().size) {
            avisos += "Hay archivos del modelo repetidos (iguales byte a byte) en la carpeta: va solo uno de cada " +
                "(${(m.modelos() - modelos.toSet()).joinToString(", ") { it.fileName.toString() }} no van)."
        }
        val archivos = m.imagenes().mapIndexed { i, p -> Archivo(p.fileName.toString(), if (i == 0) Tipo.PORTADA else Tipo.FOTO) } +
            modelos.map { Archivo(it.fileName.toString(), Tipo.MODELO) }

        return Ficha(
            clave = clave,
            titulo = meta.title().trim(),
            resumen = resumen,
            descripcion = descripcion,
            etiquetas = etiquetas,
            categoria = CatalogoPrintables.CATEGORIA, // la misma para todos (ver CATEGORIA)
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

    /** Apunta en la carpeta que ya está en Printables (id del modelo, dirección y fecha). */
    fun anotarSubido(clave: String, modelo: String) {
        require(modelo.isNotBlank() && modelo.all { it.isDigit() }) { "id de modelo no válido: $modelo" }
        val dir = carpeta(clave)
        Files.writeString(
            dir.resolve(REGISTRO),
            "$modelo\t${direccionModelo(modelo)}\t${LocalDateTime.now().format(FECHA)}\n",
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
        const val WEB = "https://www.printables.com"
        /** El formulario de subida (comprobado el 10/11/2026). */
        const val FORMULARIO = "$WEB/model/create"
        /**
         * El formulario en "modo seguido": la extensión rellena cada modelo sola y, tras guardar, pasa al
         * siguiente (la marca va tras "#", así Printables ni la ve).
         */
        const val FORMULARIO_SEGUIDO = "$FORMULARIO#jujamaker-auto"

        /** Dentro de la carpeta del modelo: lo subido a Printables (id del modelo, dirección y fecha). */
        const val REGISTRO = "subido-printables.txt"

        private const val PENDIENTES = "pendientes"
        private const val SUBIDOS = "subidos"
        private val FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /**
         * Los archivos sin los que son iguales byte a byte a uno anterior de la lista (se compara el
         * tamaño y, si coincide, el SHA-256). Si uno no se puede leer, se queda: ya fallará al pedirlo.
         */
        @JvmStatic
        fun sinRepetidos(archivos: List<Path>): List<Path> {
            val vistos = HashSet<Pair<Long, String>>()
            return archivos.filter { p ->
                try {
                    vistos.add(Files.size(p) to resumenSha256(p))
                } catch (_: IOException) {
                    true
                }
            }
        }

        private fun resumenSha256(p: Path): String {
            val md = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(p).use { e ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = e.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return HexFormat.of().formatHex(md.digest())
        }

        /** Si esa carpeta ya se subió a Printables. */
        @JvmStatic
        fun yaSubido(carpeta: Path): Boolean = Files.exists(carpeta.resolve(REGISTRO))

        /** Dónde se edita un modelo en Printables (al guardar el borrador, la web salta ahí; 10/11/2026). */
        @JvmStatic
        fun direccionModelo(id: String): String = "$WEB/model/$id/edit"

        /** Una etiqueta como la admite Printables: minúsculas, sin tildes y solo letras a-z y números ("Scale-Model" → "scalemodel"). */
        @JvmStatic
        fun etiquetaPrintables(etiqueta: String): String =
            Normalizer.normalize(etiqueta.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("[^a-z0-9]"), "")

        /**
         * El resumen: la primera línea con texto de la descripción, cortada por una palabra si pasa de
         * [CatalogoPrintables.MAX_RESUMEN] caracteres (con "…" al final, que también cuenta).
         */
        @JvmStatic
        fun resumenDe(descripcion: String): String {
            val linea = descripcion.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return ""
            val max = CatalogoPrintables.MAX_RESUMEN
            if (linea.length <= max) return linea
            val corte = linea.take(max - 1).substringBeforeLast(' ', linea.take(max - 1)).trimEnd(' ', ',', ';', ':', '.')
            return "$corte…"
        }
    }
}
