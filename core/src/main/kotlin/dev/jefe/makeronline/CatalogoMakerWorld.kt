package dev.jefe.makeronline

/**
 * Las categorías y licencias de MakerWorld y cómo se traduce a ellas cada categoría de MakerOnline
 * (igual que CatalogoCreality: los textos de cada modelo llevan la categoría de MakerOnline y aquí se
 * busca la equivalente, así no hay que escribir nada nuevo).
 *
 * **ESQUELETO, vacío a propósito.** No se rellena de memoria: hay que sacarlo de la web (formulario de
 * subida o la respuesta de su API) y anotar la fecha en que se comprobó, como en los otros catálogos.
 * Ver docs/contexto-makerworld.md.
 */
object CatalogoMakerWorld {

    /** Una categoría de MakerWorld: su id en la web y su nombre tal como sale en el formulario. */
    data class Categoria(val id: String, val nombre: String)

    /** Primer nivel → sus subcategorías, en el orden de la web. TODO(makerworld): rellenar desde la web. */
    @JvmField
    val CATEGORIAS: Map<Categoria, List<Categoria>> = linkedMapOf()

    /** Licencias, con el texto exacto de cada opción. TODO(makerworld): rellenar desde la web. */
    @JvmField
    val LICENCIAS: List<String> = emptyList()

    /**
     * Categoría de MakerOnline ("Toys&Games" / "Action Figures") → id de la de MakerWorld.
     * TODO(makerworld): rellenar cuando esté CATEGORIAS.
     */
    private val DESDE_MAKERONLINE: Map<Pair<String, String?>, String> = emptyMap()

    /** La categoría de MakerWorld equivalente a una de MakerOnline, o null si no se sabe todavía. */
    @JvmStatic
    fun desdeMakerOnline(primerNivel: String, segundoNivel: String?): Categoria? {
        val id = DESDE_MAKERONLINE[primerNivel to segundoNivel] ?: DESDE_MAKERONLINE[primerNivel to null] ?: return null
        return CATEGORIAS.entries.asSequence()
            .flatMap { (grupo, subs) -> sequenceOf(grupo) + subs.asSequence() }
            .firstOrNull { it.id == id }
    }
}
