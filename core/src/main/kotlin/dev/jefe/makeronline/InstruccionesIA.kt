package dev.jefe.makeronline

import java.text.Normalizer

/**
 * Lo que la app le pide a una IA para escribir los textos de un modelo, y cómo se lee lo que contesta.
 *
 * Sin clave: la persona copia las instrucciones, las pega en su IA (ChatGPT, Gemini, Claude…) y
 * pega aquí la respuesta. Más adelante, con clave propia, la app se las mandará directamente.
 *
 * A la IA solo se le piden título, categoría, etiquetas y descripción: la licencia y el tipo de
 * impresión los decide quien sube el modelo.
 *
 * Los límites son los contadores del formulario de MakerOnline ("0 / 100", "0 / 20" y "0 / 9000",
 * vistos el 31/10/2026).
 */
object InstruccionesIA {

    const val MAX_TITULO = 100
    const val MAX_ETIQUETAS = 20
    const val MAX_DESCRIPCION = 9000

    /** En qué idioma se piden los textos. */
    enum class Idioma(val enInstrucciones: String) {
        INGLES("inglés"),
        ESPANOL("español"),
    }

    /** Las categorías como se le dan a la IA: "Categoría > Subcategoría" (o solo la categoría si no tiene). */
    @JvmStatic
    fun categorias(): List<String> = CatalogoMakerOnline.CATEGORIAS.flatMap { (cat, subs) ->
        if (subs.isEmpty()) listOf(cat) else subs.map { "$cat > $it" }
    }

    /**
     * Las instrucciones para pegar en la IA.
     *
     * @param carpeta el nombre de la carpeta del modelo (a veces es lo único que se sabe)
     * @param notas   lo que la persona sabe del modelo (puede estar vacío)
     * @param actual  los textos que ya tiene la carpeta, para que los mejore (o null)
     */
    @JvmStatic
    fun crear(carpeta: String, notas: String, idioma: Idioma, actual: ModelMeta?): String = buildString {
        append("Eres un experto en publicar modelos de impresión 3D en MakerOnline. Escribe los textos ")
        append("de la ficha de este modelo con la información de abajo (y con la foto, si te la adjunto).\n\n")
        append("Reglas:\n")
        append("- Escribe el título, las etiquetas y la descripción en ${idioma.enInstrucciones}.\n")
        append("- Título: claro y atractivo, de $MAX_TITULO caracteres como mucho.\n")
        append("- Etiquetas: entre 10 y $MAX_ETIQUETAS, cortas, separadas por comas, sin # ni comillas.\n")
        append("- Descripción: qué es, para qué sirve y consejos de impresión si se deducen de la información. ")
        append("No te inventes medidas, materiales ni datos técnicos. Puede tener varios párrafos. ")
        append("Como mucho $MAX_DESCRIPCION caracteres.\n")
        append("- Categoría: elige la más adecuada de esta lista y cópiala exactamente igual:\n")
        for (c in categorias()) append("  ").append(c).append('\n')
        append('\n')
        append("Responde solo con el bloque de abajo, sin nada antes ni después y sin formato ")
        append("(ni **, ni #, ni ```). Escribe las palabras Título, Categoría, Etiquetas y Descripción ")
        append("tal cual, aunque los textos vayan en otro idioma:\n\n")
        append("Título: ...\n")
        append("Categoría: ...\n")
        append("Etiquetas: ..., ..., ...\n")
        append("Descripción:\n")
        append("...\n\n")
        append("Información del modelo:\n")
        append("Nombre de la carpeta: ").append(carpeta).append('\n')
        if (notas.isNotBlank()) {
            append(notas.trim()).append('\n')
        } else {
            append("(no hay más información: guíate por el nombre de la carpeta y la foto)\n")
        }
        if (actual != null &&
            (!actual.title().isNullOrBlank() || !actual.description().isNullOrBlank() || !actual.tags().isNullOrEmpty())
        ) {
            append("\nTextos que ya tiene (mejóralos):\n")
            if (!actual.title().isNullOrBlank()) append("Título: ").append(actual.title()).append('\n')
            if (!actual.tags().isNullOrEmpty()) append("Etiquetas: ").append(actual.tags().joinToString(", ")).append('\n')
            if (!actual.description().isNullOrBlank()) append("Descripción:\n").append(actual.description()).append('\n')
        }
    }

    // ------------------------------------------------------------ leer la respuesta

    /** Una línea "Nombre: valor", aunque la IA haya puesto negritas, viñetas o el nombre en inglés. */
    private val LINEA_CLAVE = Regex(
        "^\\s*(?:[-*•]\\s+)?[*_]{0,2}\\s*(t[ií]tulo|title|categor[ií]a|category|etiquetas|tags|licencia|license|tipo|" +
            "descripci[oó]n|description)\\s*[*_]{0,2}\\s*:\\s*[*_]{0,2}\\s*(.*?)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Lee lo que ha contestado la IA. Quita lo que suelen añadir (una frase antes del bloque,
     * ``` , negritas, # en las etiquetas…) y arregla la categoría si viene un poco distinta
     * ("Toys & Games - Action Figures" → Toys&Games > Action Figures).
     *
     * @throws IllegalStateException si no se encuentra el título (con el motivo para la persona)
     */
    @JvmStatic
    fun leerRespuesta(respuesta: String): ModelMeta {
        val lineas = respuesta.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n').lines()
            .filterNot { it.trim().startsWith("```") }
            .map { linea ->
                val m = LINEA_CLAVE.matchEntire(linea) ?: return@map linea
                m.groupValues[1] + ": " + m.groupValues[2].removeSuffix("**").removeSuffix("__").trim()
            }
        // Lo que haya antes de la primera línea "Nombre: valor" (p. ej. "¡Claro! Aquí tienes:") sobra
        val inicio = lineas.indexOfFirst { LINEA_CLAVE.matches(it) }
        val limpio = if (inicio >= 0) lineas.drop(inicio).joinToString("\n") else respuesta
        val meta = try {
            TextosModelo.interpretar(limpio)
        } catch (e: IllegalStateException) {
            TextosModelo.interpretarApartados(respuesta) ?: throw IllegalStateException(
                "No entiendo la respuesta: tiene que empezar por \"Título:\". Copia la respuesta entera de la IA " +
                    "y, si no sale así, vuelve a copiar las instrucciones y pídeselo otra vez.",
            )
        }
        if (meta.title().isNullOrBlank()) {
            throw IllegalStateException("La respuesta no trae el título (\"Título: …\"). Copia la respuesta entera de la IA.")
        }
        return ModelMeta(
            meta.title().trim().trim('"', '*'),
            meta.description()?.trim(),
            limpiarEtiquetas(meta.tags().orEmpty()),
            ajustarCategoria(meta.category()),
            meta.license(),
            meta.printType(),
        )
    }

    /** Sin #, comillas ni duplicados; "#gato #llavero" (todo junto) se separa. */
    private fun limpiarEtiquetas(etiquetas: List<String>): List<String> = etiquetas
        .flatMap { e -> if (e.count { it == '#' } > 1) e.split('#') else listOf(e) }
        .map { it.trim().trim('#', '"', '\'', '`', '*', '.').trim() }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }

    /**
     * La categoría con el nombre exacto del catálogo, si se parece a una (sin contar espacios,
     * mayúsculas ni tildes). Si no se parece a ninguna, se deja como ha venido: la ventana
     * Descripción avisa de que no existe.
     */
    @JvmStatic
    fun ajustarCategoria(niveles: List<String>?): List<String>? {
        if (niveles.isNullOrEmpty()) return niveles
        val partes = if (niveles.size == 1) niveles[0].split(" - ", "/", "›").map { it.trim() }.filter { it.isNotEmpty() } else niveles
        val cat = CatalogoMakerOnline.CATEGORIAS.keys.firstOrNull { clave(it) == clave(partes[0]) } ?: return partes
        val subs = CatalogoMakerOnline.CATEGORIAS[cat].orEmpty()
        if (partes.size < 2) return listOf(cat)
        val sub = subs.firstOrNull { clave(it) == clave(partes[1]) } ?: partes[1]
        return listOf(cat, sub)
    }

    private fun clave(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
            .lowercase().replace(Regex("[^a-z0-9]"), "")

    /** Lo que conviene revisar de los textos que ha escrito la IA (vacío si está todo bien). */
    @JvmStatic
    fun revisar(meta: ModelMeta): List<String> = buildList {
        val titulo = meta.title().orEmpty()
        if (titulo.isBlank()) add("falta el título")
        else if (titulo.length > MAX_TITULO) add("el título tiene ${titulo.length} caracteres (máximo $MAX_TITULO): acórtalo")
        val n = meta.tags().orEmpty().size
        if (n == 0) add("faltan las etiquetas")
        else if (n > MAX_ETIQUETAS) add("hay $n etiquetas y MakerOnline admite $MAX_ETIQUETAS: se quedan las $MAX_ETIQUETAS primeras")
        val desc = meta.description().orEmpty()
        if (desc.isBlank()) add("falta la descripción")
        else if (desc.length > MAX_DESCRIPCION) add("la descripción tiene ${desc.length} caracteres (máximo $MAX_DESCRIPCION): acórtala")
        if (meta.sinCategoria()) add("falta la categoría: se pondrá la de por defecto")
        else if (!CatalogoMakerOnline.categoriaCompleta(meta.category())) add("la categoría está incompleta: elige la subcategoría")
        addAll(CatalogoMakerOnline.desconocidos(meta))
    }
}
