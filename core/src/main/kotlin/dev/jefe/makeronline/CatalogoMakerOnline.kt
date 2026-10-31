package dev.jefe.makeronline

/**
 * Las opciones del formulario de subida de MakerOnline (licencia, categoría y tipo de impresión),
 * para los desplegables de la app y para avisar de lo que no existe en la web.
 *
 * Comprobado el 31/10/2026 con el "Diagnóstico del formulario" (diagnostico-formulario.txt).
 * Si MakerOnline cambia sus opciones, se vuelve a lanzar el diagnóstico y se actualiza aquí.
 */
object CatalogoMakerOnline {

    /** La licencia que se elige si no se dice otra. */
    const val LICENCIA_POR_DEFECTO = "CC0"

    /** La categoría que se elige si no se dice otra (se puede cambiar luego en el borrador de MakerOnline). */
    @JvmField
    val CATEGORIA_POR_DEFECTO: List<String> = listOf("Toys&Games", "Action Figures")

    /** El tipo de impresión que se marca si no se dice otro. */
    const val TIPO_POR_DEFECTO = "FDM"

    /** Licencias, con el título exacto de cada opción de la ventana "License". */
    @JvmField
    val LICENCIAS: List<String> = listOf(
        "CC BY", "CC BY-SA", "CC BY-NC", "CC BY-NC-SA", "CC BY-ND", "CC BY-NC-ND", "CC0",
        "Standard Digital File License",
    )

    /** Tipos de impresión ("Printing Method"), con el texto exacto de cada opción. */
    @JvmField
    val TIPOS: List<String> = listOf("FDM", "Resin", "Both are fine")

    /** Categorías: primer nivel → sus subcategorías, en el orden de la web. Vacía = no tiene segundo nivel. */
    @JvmField
    val CATEGORIAS: Map<String, List<String>> = linkedMapOf(
        "3D Printer" to listOf("3D Printer Parts & Upgrades", "3D Printer Accessories", "Test Models"),
        "Household" to listOf("Home Decor", "Garden", "Kitchen", "Office", "Festivities", "Living Room", "Pets", "Other Household"),
        "Hobby&DIY" to listOf("Automotive", "Electronics", "Music", "Sports&Outdoor", "RC", "Robotics", "Mechanical Parts", "Other Hobby&DIY"),
        "Tools" to listOf("Gadgets", "Organizers", "Hand Tools", "Machine Tools", "Medical Tools", "Other Tools"),
        "Toys&Games" to listOf("Action Figures", "Characters", "Board Games", "Building Toys", "Outdoor Toys", "Puzzle", "Vehicles", "Other Toys&Games"),
        "Art" to listOf("2D Plate", "Sculptures & Statues", "Animals", "Signs & Logos", "Badges", "Other Art"),
        "Fashion" to listOf("Accessories", "Bags & Purses", "Clothing", "Jewelry", "Other Fashion"),
        "Education" to listOf("Biology", "Chemistry", "Geography", "Engineering", "Physics &Astronomy", "Mathematics", "Other Education"),
        "Costumes & Cosplay" to listOf("Costumes", "Masks", "Props", "Other Costumes & Cosplay"),
        "Miniatures" to listOf("Characters", "Architecture", "Creatures", "Gaming Accessories", "Fantasy&Sci-Fi", "Other Miniatures"),
        "Health & Fitness" to listOf("Wellness", "Accessibility"),
        "Pop Culture" to listOf("Characters", "Memes & Trends", "Other Pop Culture"),
        "Generative 3D Model" to listOf("Make My Phone Case", "Make My Light", "AI Modeling"),
        "Creative Kit Model" to emptyList(),
    )

    /**
     * Cómo se llamaba antes la licencia por defecto (el principio de su descripción en la web).
     * Los info.json antiguos la tienen así: se trata como "CC0".
     */
    const val CC0_ANTIGUA = "CC0 (aka CC Zero)"

    /** La licencia con el nombre del catálogo ("CC0 (aka CC Zero)" → "CC0"). */
    @JvmStatic
    fun normalizarLicencia(licencia: String?): String? =
        if (licencia != null && licencia.trim().equals(CC0_ANTIGUA, ignoreCase = true)) "CC0" else licencia

    /** Si la categoría está completa: 2 niveles, o 1 si esa categoría no tiene subcategorías. */
    @JvmStatic
    fun categoriaCompleta(niveles: List<String?>?): Boolean {
        if (niveles == null || niveles.any { it.isNullOrBlank() }) return false
        return niveles.size == 2 || (niveles.size == 1 && CATEGORIAS[niveles[0]]?.isEmpty() == true)
    }

    /** Lo que no está en el catálogo (la web podría no tenerlo y fallaría al subir), para avisar. */
    @JvmStatic
    fun desconocidos(meta: ModelMeta): List<String> = buildList {
        val cat = meta.category().orEmpty()
        if (cat.isNotEmpty()) {
            val subs = CATEGORIAS[cat[0]]
            if (subs == null) add("la categoría \"${cat[0]}\" no está entre las de MakerOnline")
            else if (cat.size > 1 && cat[1] !in subs) add("\"${cat[1]}\" no es una subcategoría de \"${cat[0]}\"")
        }
        val lic = normalizarLicencia(meta.license())
        if (!lic.isNullOrBlank() && lic !in LICENCIAS) add("la licencia \"$lic\" no está entre las de MakerOnline")
        val tipo = meta.printType()
        if (!tipo.isNullOrBlank() && tipo !in TIPOS) add("el tipo \"$tipo\" no está entre los de MakerOnline (${TIPOS.joinToString(", ")})")
    }
}
