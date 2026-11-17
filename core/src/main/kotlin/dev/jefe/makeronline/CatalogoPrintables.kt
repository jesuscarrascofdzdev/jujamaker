package dev.jefe.makeronline

/**
 * Las categorías y licencias de Printables, la categoría con la que se sube todo ([CATEGORIA]) y cómo
 * se traduce a Printables cada licencia de MakerOnline (los textos de cada modelo llevan la de
 * MakerOnline y aquí se busca la equivalente, así no hay que escribir nada nuevo).
 *
 * Comprobado el 10/11/2026 en el formulario de subida (`https://www.printables.com/model/create`, en
 * inglés) con la extensión Claude in Chrome: las categorías son las opciones del desplegable "Main
 * category" (botones `button.option` agrupados bajo un título de grupo; solo se elige el segundo
 * nivel) y las licencias, las del desplegable "License". La web no enseña ningún id: se eligen por su
 * texto exacto (las licencias llevan rayas "—", no guiones). Si Printables cambia sus opciones, se
 * vuelve a mirar ahí y se actualiza este archivo.
 */
object CatalogoPrintables {

    /** Una categoría de Printables: su grupo y su nombre, tal como salen en el desplegable. */
    data class Categoria(val grupo: String, val nombre: String)

    /** Grupo → sus categorías, en el orden de la web. */
    @JvmField
    val CATEGORIAS: Map<String, List<String>> = linkedMapOf(
        "3D Printers" to listOf("Prusa Parts & Upgrades", "Accessories", "Anycubic Parts & Upgrades",
            "Bambu Lab Parts & Upgrades", "Creality Parts & Upgrades", "Other Printer Parts & Upgrades",
            "Voron Parts & Upgrades", "Test Models"),
        "Art & Design" to listOf("2D Plates & Logos", "Sculptures", "Wall-mounted", "Other Art & Designs"),
        "Costumes & Accessories" to listOf("Cosplay & Costumes in general", "Masks", "Props", "Other Costume Accessories"),
        "Fashion" to listOf("Men", "Women", "Other Fashion Accessories"),
        "Gadgets" to listOf("Audio", "Computers", "Photo & Video", "Portable Devices", "Video Games",
            "Virtual Reality", "Other Gadgets"),
        "Healthcare" to listOf("Home Medical Tools", "Medical Tools"),
        "Hobby & Makers" to listOf("Automotive", "Electronics", "Mechanical Parts", "Music", "Organizers",
            "RC & Robotics", "Tools", "Other Ideas"),
        "Household" to listOf("Bathroom", "Bedroom", "Garage", "Home Decor", "Kitchen", "Living Room", "Office",
            "Outdoor & Garden", "Other House Equipment", "Pets"),
        "Learning" to listOf("Chemistry & Biology", "Engineering", "Haptic Models", "Math",
            "Other 3D Objects for Learning", "Physics & Astronomy"),
        "Seasonal designs" to listOf("Autumn & Halloween", "Spring & Easter", "Summer", "Winter & Christmas & New Year's"),
        "Sports & Outdoor" to listOf("Indoor Sports", "Other Sports", "Outdoor Sports", "Winter Sports"),
        "Tabletop Miniatures" to listOf("Characters & Monsters", "Miniature Gaming Accessories", "Props & Terrains",
            "Vehicles & Machines"),
        "Toys & Games" to listOf("Action Figures & Statues", "Board Games", "Building Toys", "Outdoor Toys",
            "Puzzles & Brain-teasers", "Vehicles", "Other Toys & Games"),
        "World & Scans" to listOf("Animals", "Architecture & Urbanism", "Historical Context", "People"),
    )

    /**
     * La categoría con la que se suben todos los modelos. Decisión de la persona (10/11/2026): una sola,
     * amplia, que valga para cualquier modelo, en vez de traducir la de MakerOnline (las equivalencias no
     * siempre acertaban y había que corregirlas a mano). Se puede cambiar en Printables antes de publicar.
     */
    @JvmField
    val CATEGORIA = Categoria("Toys & Games", "Other Toys & Games").also {
        require(CATEGORIAS[it.grupo]?.contains(it.nombre) == true) { "no existe en Printables: ${it.grupo} > ${it.nombre}" }
    }

    /** Las opciones del desplegable "License", con su texto exacto y en el orden de la web. */
    @JvmField
    val LICENCIAS: List<String> = listOf(
        "Creative Commons — Public Domain",
        "Creative Commons — Attribution",
        "Creative Commons — Attribution — Share Alike",
        "Creative Commons — Attribution — NoDerivatives",
        "Creative Commons — Attribution — Noncommercial",
        "Creative Commons — Attribution — Noncommercial — Share Alike",
        "Creative Commons — Attribution — Noncommercial — NoDerivatives",
        "GNU General Public License v2.0",
        "GNU General Public License v3.0",
        "GNU Lesser General Public License",
        "BSD License",
        "Standard Digital File License",
        "Open Community License v1.1",
        "Open Community License v1.1 + General Attribution v1",
        "Open Community License v1.1 + Research & Development v1",
        "Open Community License v1.1 + Micro Business v1",
        "Open Community License v1.1 + GAtt v1 + Micro v1",
        "Open Community License v1.1 + GAtt v1 + RnD v1",
        "CERN Open Hardware Licence Version 2 - Strongly Reciprocal",
        "Apache License 2.0",
    )

    /** Licencia de MakerOnline → la de Printables (todas las de MakerOnline tienen equivalente). */
    private val LICENCIAS_MAKERONLINE: Map<String, String> = mapOf(
        "CC0" to "Creative Commons — Public Domain",
        "CC BY" to "Creative Commons — Attribution",
        "CC BY-SA" to "Creative Commons — Attribution — Share Alike",
        "CC BY-ND" to "Creative Commons — Attribution — NoDerivatives",
        "CC BY-NC" to "Creative Commons — Attribution — Noncommercial",
        "CC BY-NC-SA" to "Creative Commons — Attribution — Noncommercial — Share Alike",
        "CC BY-NC-ND" to "Creative Commons — Attribution — Noncommercial — NoDerivatives",
        "Standard Digital File License" to "Standard Digital File License",
    )

    /** La licencia que se elige si la de MakerOnline no tiene equivalente (la por defecto de MakerOnline, CC0). */
    const val LICENCIA_POR_DEFECTO = "Creative Commons — Public Domain"

    /** El texto de Printables para una licencia de MakerOnline, o null si no hay equivalente. */
    @JvmStatic
    fun licenciaDeMakerOnline(licencia: String?): String? {
        val l = CatalogoMakerOnline.normalizarLicencia(licencia)?.trim() ?: return null
        return LICENCIAS_MAKERONLINE.entries.firstOrNull { it.key.equals(l, ignoreCase = true) }?.value
    }

    /**
     * "Summary (required)": un textarea de 120 caracteres como mucho (maxlength=120 y contador
     * "0/120", 10/11/2026). MakerOnline no tiene resumen: se saca del principio de la descripción.
     */
    const val MAX_RESUMEN = 120
}
