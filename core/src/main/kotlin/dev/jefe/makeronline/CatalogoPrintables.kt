package dev.jefe.makeronline

/**
 * Las categorías y licencias de Printables y cómo se traduce a ellas cada categoría y licencia de
 * MakerOnline (igual que CatalogoCreality: los textos de cada modelo llevan las de MakerOnline y aquí
 * se busca la equivalente, así no hay que escribir nada nuevo).
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

    /** La que se elige si la de MakerOnline no tiene equivalente. */
    @JvmField
    val POR_DEFECTO = Categoria("Toys & Games", "Other Toys & Games")

    /** Categoría de MakerOnline (primer nivel, segundo nivel o "") → la de Printables. Es una propuesta: revisable. */
    private val EQUIVALENCIAS: Map<Pair<String, String>, Categoria> = buildMap {
        fun de(grupoMO: String, subMO: String, grupo: String, nombre: String) {
            require(CATEGORIAS[grupo]?.contains(nombre) == true) { "no existe en Printables: $grupo > $nombre" }
            put(grupoMO to subMO, Categoria(grupo, nombre))
        }
        de("3D Printer", "3D Printer Parts & Upgrades", "3D Printers", "Other Printer Parts & Upgrades")
        de("3D Printer", "3D Printer Accessories", "3D Printers", "Accessories")
        de("3D Printer", "Test Models", "3D Printers", "Test Models")

        de("Household", "Home Decor", "Household", "Home Decor")
        de("Household", "Garden", "Household", "Outdoor & Garden")
        de("Household", "Kitchen", "Household", "Kitchen")
        de("Household", "Office", "Household", "Office")
        de("Household", "Festivities", "Household", "Home Decor")
        de("Household", "Living Room", "Household", "Living Room")
        de("Household", "Pets", "Household", "Pets")
        de("Household", "Other Household", "Household", "Other House Equipment")

        de("Hobby&DIY", "Automotive", "Hobby & Makers", "Automotive")
        de("Hobby&DIY", "Electronics", "Hobby & Makers", "Electronics")
        de("Hobby&DIY", "Music", "Hobby & Makers", "Music")
        de("Hobby&DIY", "Sports&Outdoor", "Sports & Outdoor", "Other Sports")
        de("Hobby&DIY", "RC", "Hobby & Makers", "RC & Robotics")
        de("Hobby&DIY", "Robotics", "Hobby & Makers", "RC & Robotics")
        de("Hobby&DIY", "Mechanical Parts", "Hobby & Makers", "Mechanical Parts")
        de("Hobby&DIY", "Other Hobby&DIY", "Hobby & Makers", "Other Ideas")

        de("Tools", "Gadgets", "Gadgets", "Other Gadgets")
        de("Tools", "Organizers", "Hobby & Makers", "Organizers")
        de("Tools", "Hand Tools", "Hobby & Makers", "Tools")
        de("Tools", "Machine Tools", "Hobby & Makers", "Tools")
        de("Tools", "Medical Tools", "Healthcare", "Medical Tools")
        de("Tools", "Other Tools", "Hobby & Makers", "Tools")

        de("Toys&Games", "Action Figures", "Toys & Games", "Action Figures & Statues")
        de("Toys&Games", "Characters", "Toys & Games", "Action Figures & Statues")
        de("Toys&Games", "Board Games", "Toys & Games", "Board Games")
        de("Toys&Games", "Building Toys", "Toys & Games", "Building Toys")
        de("Toys&Games", "Outdoor Toys", "Toys & Games", "Outdoor Toys")
        de("Toys&Games", "Puzzle", "Toys & Games", "Puzzles & Brain-teasers")
        de("Toys&Games", "Vehicles", "Toys & Games", "Vehicles")
        de("Toys&Games", "Other Toys&Games", "Toys & Games", "Other Toys & Games")

        de("Art", "2D Plate", "Art & Design", "2D Plates & Logos")
        de("Art", "Sculptures & Statues", "Art & Design", "Sculptures")
        de("Art", "Animals", "World & Scans", "Animals")
        de("Art", "Signs & Logos", "Art & Design", "2D Plates & Logos")
        de("Art", "Badges", "Art & Design", "2D Plates & Logos")
        de("Art", "Other Art", "Art & Design", "Other Art & Designs")

        for (sub in listOf("Accessories", "Bags & Purses", "Clothing", "Jewelry", "Other Fashion")) {
            de("Fashion", sub, "Fashion", "Other Fashion Accessories")
        }

        de("Education", "Biology", "Learning", "Chemistry & Biology")
        de("Education", "Chemistry", "Learning", "Chemistry & Biology")
        de("Education", "Geography", "Learning", "Other 3D Objects for Learning")
        de("Education", "Engineering", "Learning", "Engineering")
        de("Education", "Physics &Astronomy", "Learning", "Physics & Astronomy")
        de("Education", "Mathematics", "Learning", "Math")
        de("Education", "Other Education", "Learning", "Other 3D Objects for Learning")

        de("Costumes & Cosplay", "Costumes", "Costumes & Accessories", "Cosplay & Costumes in general")
        de("Costumes & Cosplay", "Masks", "Costumes & Accessories", "Masks")
        de("Costumes & Cosplay", "Props", "Costumes & Accessories", "Props")
        de("Costumes & Cosplay", "Other Costumes & Cosplay", "Costumes & Accessories", "Other Costume Accessories")

        de("Miniatures", "Characters", "Tabletop Miniatures", "Characters & Monsters")
        de("Miniatures", "Architecture", "Tabletop Miniatures", "Props & Terrains")
        de("Miniatures", "Creatures", "Tabletop Miniatures", "Characters & Monsters")
        de("Miniatures", "Gaming Accessories", "Tabletop Miniatures", "Miniature Gaming Accessories")
        de("Miniatures", "Fantasy&Sci-Fi", "Tabletop Miniatures", "Characters & Monsters")
        de("Miniatures", "Other Miniatures", "Tabletop Miniatures", "Props & Terrains")

        de("Health & Fitness", "Wellness", "Healthcare", "Home Medical Tools")
        de("Health & Fitness", "Accessibility", "Healthcare", "Home Medical Tools")

        de("Pop Culture", "Characters", "Toys & Games", "Action Figures & Statues")
        de("Pop Culture", "Memes & Trends", "Art & Design", "Other Art & Designs")
        de("Pop Culture", "Other Pop Culture", "Art & Design", "Other Art & Designs")

        de("Generative 3D Model", "Make My Phone Case", "Gadgets", "Portable Devices")
        de("Generative 3D Model", "Make My Light", "Household", "Home Decor")
        de("Generative 3D Model", "AI Modeling", "Art & Design", "Other Art & Designs")
        de("Creative Kit Model", "", "Art & Design", "Other Art & Designs")
    }

    /**
     * La categoría de Printables para una de MakerOnline (["Toys&Games", "Action Figures"]).
     * Sin categoría se usa la de por defecto de MakerOnline (Toys&Games > Action Figures).
     */
    @JvmStatic
    fun paraMakerOnline(categoriaMakerOnline: List<String>?): Categoria {
        val cat = if (categoriaMakerOnline.isNullOrEmpty() || categoriaMakerOnline.all { it.isBlank() }) {
            CatalogoMakerOnline.CATEGORIA_POR_DEFECTO
        } else categoriaMakerOnline
        return EQUIVALENCIAS[cat[0] to (cat.getOrNull(1) ?: "")] ?: POR_DEFECTO
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
