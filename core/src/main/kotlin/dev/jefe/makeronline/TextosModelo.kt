package dev.jefe.makeronline

import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.text.Normalizer

/**
 * Los textos de un modelo escritos en un .txt (con el Bloc de notas, por ejemplo), en vez de
 * en info.json. Cada dato en su línea, como "Nombre: valor"; la descripción va al final y
 * ocupa todas las líneas que sigan:
 *
 * ```
 * Título: Llavero gato
 * Categoría: Toys&Games > Action Figures
 * Etiquetas: gato, llavero, regalo
 * Licencia: CC0                    (opcional)
 * Tipo: FDM                        (opcional)
 * Descripción:
 * Todo lo que venga después, con sus saltos de línea.
 * ```
 *
 * Los nombres valen con o sin tilde y en mayúsculas o minúsculas, y también en inglés
 * (Title, Category, Tags, License, Print type, Description). Antes de la descripción se
 * ignoran las líneas vacías y las que empiezan por # (comentarios).
 * Lo que va entre corchetes, como "[título del modelo]", cuenta como sin rellenar (ver ModelMeta).
 *
 * También entiende textos por apartados, como los que da una IA: una línea con el nombre del
 * apartado ("Model Title", "20 Tags", "Detailed Description", "Título", "Etiquetas"…) y debajo su
 * contenido. Lo que hay antes del primer apartado se ignora. Ver [interpretarApartados].
 */
object TextosModelo {

    /** Nombre (sin tildes, en minúsculas) → qué dato es. */
    private val CLAVES = mapOf(
        "titulo" to "titulo", "title" to "titulo",
        "categoria" to "categoria", "category" to "categoria",
        "etiquetas" to "etiquetas", "tags" to "etiquetas",
        "licencia" to "licencia", "license" to "licencia",
        "tipo" to "tipo", "tipo de impresion" to "tipo", "print type" to "tipo", "printtype" to "tipo",
        "descripcion" to "descripcion", "description" to "descripcion",
    )

    /**
     * Lee el .txt. Admite UTF-8 (lo normal en el Bloc de notas de Windows 10 y 11) y, si no lo es,
     * la codificación antigua de Windows.
     *
     * @throws IllegalStateException con todas las líneas que no se entienden
     */
    @JvmStatic
    fun leer(archivo: Path): ModelMeta {
        val bytes = Files.readAllBytes(archivo)
        val texto = try {
            Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1252"))
        }
        return interpretar(texto)
    }

    /** Los textos en formato .txt (lo contrario de [interpretar]), para guardarlos desde la app. */
    @JvmStatic
    fun escribir(meta: ModelMeta): String = buildString {
        append("# Textos del modelo (JujaMaker)\n")
        append("Título: ").append(meta.title() ?: "").append('\n')
        append("Categoría: ").append(meta.category()?.joinToString(" > ") ?: "").append('\n')
        append("Etiquetas: ").append(meta.tags()?.joinToString(", ") ?: "").append('\n')
        meta.license()?.let { append("Licencia: ").append(it).append('\n') }
        meta.printType()?.let { append("Tipo: ").append(it).append('\n') }
        append("Descripción:\n")
        append(meta.description() ?: "").append('\n')
    }

    /**
     * Igual que [leer], con el texto ya leído: primero con el formato "Nombre: valor" y, si no
     * encaja, por apartados. Si tampoco, lanza los problemas del formato "Nombre: valor".
     */
    @JvmStatic
    fun interpretar(texto: String): ModelMeta {
        val limpio = texto.removePrefix("\uFEFF")
        return try {
            interpretarClaves(limpio)
        } catch (e: IllegalStateException) {
            interpretarApartados(limpio) ?: throw e
        }
    }

    // ------------------------------------------------------------ por apartados

    /** Qué es cada apartado, según su nombre (sin tildes, en minúsculas, sin # ni ** ni ":"). */
    private val APARTADOS = listOf(
        Regex("^(model title|title|titulo|titulo del modelo|nombre del modelo)$") to "titulo",
        Regex("^(\\d+\\s+)?(tags|etiquetas)(\\s*\\(\\d+\\))?$") to "etiquetas",
        Regex("^(detailed description|full description|description|descripcion|descripcion detallada)$") to "descripcion",
        Regex("^(category|categoria)$") to "categoria",
        Regex("^(license|licencia)$") to "licencia",
        Regex("^(print type|printing method|tipo|tipo de impresion)$") to "tipo",
    )

    /**
     * Texto por apartados: cada apartado es una línea con su nombre y debajo su contenido, hasta
     * el siguiente apartado. Del título y la categoría, la licencia y el tipo se toma la primera
     * línea; las etiquetas van separadas por comas o una por línea (se quitan `, " y #); la
     * descripción es todo lo que sigue, tal cual. Devuelve null si no hay apartado de título.
     */
    @JvmStatic
    fun interpretarApartados(texto: String): ModelMeta? {
        val contenido = LinkedHashMap<String, MutableList<String>>()
        var actual: MutableList<String>? = null // null = antes del primer apartado: se ignora
        for (linea in texto.lines()) {
            val apartado = apartado(linea)
            if (apartado != null && apartado !in contenido) {
                actual = ArrayList<String>().also { contenido[apartado] = it }
            } else {
                actual?.add(linea.trimEnd())
            }
        }
        if ("titulo" !in contenido) return null
        fun primera(clave: String) = contenido[clave]?.firstOrNull { it.isNotBlank() }?.trim()
        val etiquetas = contenido["etiquetas"].orEmpty()
            .flatMap { it.split(',') }
            .map { it.trim().trim('`', '"', '\'', '*').removePrefix("#").trim().trimEnd('.') }
            .filter { it.isNotEmpty() }
            .distinct()
        val descripcion = contenido["descripcion"].orEmpty()
            .dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
        return ModelMeta(
            primera("titulo"),
            descripcion,
            etiquetas,
            primera("categoria")?.split('>', '/')?.flatMap { it.split(" - ") }?.map { it.trim() }?.filter { it.isNotEmpty() },
            primera("licencia"),
            primera("tipo"),
        )
    }

    /** Si la línea es el nombre de un apartado, cuál; si no, null. */
    private fun apartado(linea: String): String? {
        val t = linea.trim()
        if (t.isEmpty() || t.length > 40) return null
        val nombre = normalizar(t.trimStart('#').trim().removeSurrounding("**").removeSurrounding("__").trimEnd(':').trim())
        return APARTADOS.firstOrNull { (patron, _) -> patron.matches(nombre) }?.second
    }

    // ------------------------------------------------------------ "Nombre: valor"

    private fun interpretarClaves(texto: String): ModelMeta {
        val datos = HashMap<String, String>()
        val descripcion = ArrayList<String>()
        val problemas = ArrayList<String>()
        var enDescripcion = false
        for ((i, linea) in texto.lines().withIndex()) {
            if (enDescripcion) {
                descripcion += linea.trimEnd()
                continue
            }
            if (linea.isBlank() || linea.trimStart().startsWith("#")) continue
            val dosPuntos = linea.indexOf(':')
            if (dosPuntos < 0) {
                problemas += "línea ${i + 1}: no entiendo \"${linea.trim()}\" (cada dato va como Nombre: valor)"
                continue
            }
            val nombre = linea.substring(0, dosPuntos).trim()
            val valor = linea.substring(dosPuntos + 1).trim()
            when (val clave = CLAVES[normalizar(nombre)]) {
                null -> problemas += "línea ${i + 1}: no conozco \"$nombre\" " +
                    "(valen Título, Categoría, Etiquetas, Licencia, Tipo y Descripción)"
                "descripcion" -> {
                    enDescripcion = true
                    if (valor.isNotEmpty()) descripcion += valor
                }
                else -> {
                    if (clave in datos) problemas += "línea ${i + 1}: \"$nombre\" está repetido"
                    datos[clave] = valor
                }
            }
        }
        if (problemas.isNotEmpty()) throw IllegalStateException(problemas.joinToString("\n"))

        // Sin líneas vacías al principio ni al final de la descripción
        val desc = descripcion.dropWhile { it.isBlank() }.dropLastWhile { it.isBlank() }.joinToString("\n")
        return ModelMeta(
            datos["titulo"],
            desc,
            datos["etiquetas"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            datos["categoria"]?.split('>')?.map { it.trim() },
            datos["licencia"]?.ifBlank { null },
            datos["tipo"]?.ifBlank { null },
        )
    }

    private fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .lowercase().replace(Regex("\\s+"), " ").trim()
}
