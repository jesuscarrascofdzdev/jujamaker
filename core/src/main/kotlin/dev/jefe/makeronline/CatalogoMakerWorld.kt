package dev.jefe.makeronline

/**
 * Las categorías y licencias de MakerWorld y cómo se traduce a ellas cada categoría de MakerOnline
 * (igual que CatalogoCreality: los textos de cada modelo llevan la categoría de MakerOnline y aquí se
 * busca la equivalente, así no hay que escribir nada nuevo).
 *
 * Comprobado el 10/11/2026 en el formulario de subida (`/en/my/models/drafts/<id>/edit`): las
 * categorías son las opciones del autocompletado "Category" (cada una con id, name, parentId y
 * parentName; solo se elige el segundo nivel) y las licencias, las opciones del desplegable
 * `input[name=license]` (su `data-value`). El borrador guarda `categoryId` y `license` con esos valores.
 * Si MakerWorld cambia sus opciones, se vuelve a mirar ahí y se actualiza este archivo.
 */
object CatalogoMakerWorld {

    /** Una categoría de MakerWorld: su id en la web y su nombre tal como sale en el formulario. */
    data class Categoria(val id: Int, val nombre: String)

    /** Una categoría de primer nivel con las suyas de segundo nivel (las que se eligen). */
    data class Grupo(val categoria: Categoria, val subcategorias: List<Categoria>)

    /** En el orden de la web. "Generative 3D Model" es de las herramientas de MakerLab: no se usa. */
    @JvmField
    val CATEGORIAS: List<Grupo> = listOf(
        Grupo(Categoria(900, "3D Printer"), listOf(
            Categoria(901, "3D Printer Accessories"), Categoria(902, "3D Printer Parts"), Categoria(903, "Test Models"),
        )),
        Grupo(Categoria(100, "Art"), listOf(
            Categoria(101, "2D Art"), Categoria(103, "Coin & Badges"), Categoria(102, "Signs & Logos"),
            Categoria(104, "Sculptures"), Categoria(105, "Other Art Models"),
        )),
        Grupo(Categoria(500, "Education"), listOf(
            Categoria(503, "Biology"), Categoria(505, "Chemistry"), Categoria(501, "Engineering"),
            Categoria(506, "Geography"), Categoria(502, "Mathematics"), Categoria(504, "Physics & Astronomy"),
            Categoria(507, "Other Education Models"),
        )),
        Grupo(Categoria(200, "Fashion"), listOf(
            Categoria(201, "Bags"), Categoria(202, "Clothes"), Categoria(206, "Earrings"), Categoria(204, "Footwear"),
            Categoria(203, "Glasses"), Categoria(208, "Jewelry"), Categoria(205, "Rings"), Categoria(207, "Other Fashion Models"),
        )),
        Grupo(Categoria(300, "Hobby & DIY"), listOf(
            Categoria(301, "Electronics"), Categoria(303, "Music"), Categoria(304, "RC"), Categoria(305, "Robotics"),
            Categoria(306, "Sport & Outdoors"), Categoria(302, "Vehicles"), Categoria(307, "Other Hobby & DIY"),
        )),
        Grupo(Categoria(400, "Household"), listOf(
            Categoria(401, "Decor"), Categoria(403, "Festivities"), Categoria(402, "Garden"), Categoria(404, "Office"),
            Categoria(405, "Pets"), Categoria(406, "Other House Models"),
        )),
        Grupo(Categoria(600, "Miniatures"), listOf(
            Categoria(601, "Animals"), Categoria(602, "Architecture"), Categoria(603, "Creatures"),
            Categoria(604, "People"), Categoria(605, "Other Miniatures"),
        )),
        Grupo(Categoria(1000, "Props & Cosplays"), listOf(
            Categoria(1003, "Costumes"), Categoria(1001, "Masks & Helmets"), Categoria(1002, "Cosplay Weapons"),
            Categoria(1004, "Other Props & Cosplays"),
        )),
        Grupo(Categoria(700, "Tools"), listOf(
            Categoria(705, "Gadgets"), Categoria(703, "Hand Tools"), Categoria(704, "Machine Tools"),
            Categoria(702, "Measure Tools"), Categoria(707, "Medical Tools"), Categoria(701, "Organizers"),
            Categoria(706, "Other Tools"),
        )),
        Grupo(Categoria(800, "Toys & Games"), listOf(
            Categoria(802, "Board Games"), Categoria(801, "Characters"), Categoria(803, "Outdoor Toys"),
            Categoria(804, "Puzzles"), Categoria(806, "Construction Sets"), Categoria(805, "Other Toys & Games"),
        )),
    )

    /** La categoría de MakerWorld: el grupo (primer nivel) y la subcategoría (la que se elige). */
    data class Eleccion(val grupo: Categoria, val sub: Categoria) {
        override fun toString() = "${grupo.nombre} > ${sub.nombre}"
    }

    /** Busca una subcategoría por su id. */
    @JvmStatic
    fun porId(id: Int): Eleccion? {
        for (g in CATEGORIAS) g.subcategorias.firstOrNull { it.id == id }?.let { return Eleccion(g.categoria, it) }
        return null
    }

    /** Para lo que no está en la tabla (no debería pasar): Toys & Games > Other Toys & Games. */
    @JvmField
    val POR_DEFECTO: Eleccion = porId(805)!!

    /**
     * MakerOnline (primer nivel > segundo nivel) → id de la subcategoría de MakerWorld.
     * Propuesta del 10/11/2026: se revisa y se corrige aquí lo que no encaje.
     */
    private val EQUIVALENCIAS: Map<Pair<String, String>, Int> = buildMap {
        fun de(mo: String, subMo: String, mw: Int) = put(mo to subMo, mw)

        de("3D Printer", "3D Printer Parts & Upgrades", 902)
        de("3D Printer", "3D Printer Accessories", 901)
        de("3D Printer", "Test Models", 903)

        de("Household", "Home Decor", 401)
        de("Household", "Garden", 402)
        de("Household", "Kitchen", 406)
        de("Household", "Office", 404)
        de("Household", "Festivities", 403)
        de("Household", "Living Room", 401)
        de("Household", "Pets", 405)
        de("Household", "Other Household", 406)

        de("Hobby&DIY", "Automotive", 302)
        de("Hobby&DIY", "Electronics", 301)
        de("Hobby&DIY", "Music", 303)
        de("Hobby&DIY", "Sports&Outdoor", 306)
        de("Hobby&DIY", "RC", 304)
        de("Hobby&DIY", "Robotics", 305)
        de("Hobby&DIY", "Mechanical Parts", 307)
        de("Hobby&DIY", "Other Hobby&DIY", 307)

        de("Tools", "Gadgets", 705)
        de("Tools", "Organizers", 701)
        de("Tools", "Hand Tools", 703)
        de("Tools", "Machine Tools", 704)
        de("Tools", "Medical Tools", 707)
        de("Tools", "Other Tools", 706)

        de("Toys&Games", "Action Figures", 801)
        de("Toys&Games", "Characters", 801)
        de("Toys&Games", "Board Games", 802)
        de("Toys&Games", "Building Toys", 806)
        de("Toys&Games", "Outdoor Toys", 803)
        de("Toys&Games", "Puzzle", 804)
        de("Toys&Games", "Vehicles", 805)
        de("Toys&Games", "Other Toys&Games", 805)

        de("Art", "2D Plate", 101)
        de("Art", "Sculptures & Statues", 104)
        de("Art", "Animals", 104)
        de("Art", "Signs & Logos", 102)
        de("Art", "Badges", 103)
        de("Art", "Other Art", 105)

        de("Fashion", "Accessories", 207)
        de("Fashion", "Bags & Purses", 201)
        de("Fashion", "Clothing", 202)
        de("Fashion", "Jewelry", 208)
        de("Fashion", "Other Fashion", 207)

        de("Education", "Biology", 503)
        de("Education", "Chemistry", 505)
        de("Education", "Geography", 506)
        de("Education", "Engineering", 501)
        de("Education", "Physics &Astronomy", 504)
        de("Education", "Mathematics", 502)
        de("Education", "Other Education", 507)

        de("Costumes & Cosplay", "Costumes", 1003)
        de("Costumes & Cosplay", "Masks", 1001)
        de("Costumes & Cosplay", "Props", 1004)
        de("Costumes & Cosplay", "Other Costumes & Cosplay", 1004)

        de("Miniatures", "Characters", 604)
        de("Miniatures", "Architecture", 602)
        de("Miniatures", "Creatures", 603)
        de("Miniatures", "Gaming Accessories", 605)
        de("Miniatures", "Fantasy&Sci-Fi", 603)
        de("Miniatures", "Other Miniatures", 605)

        de("Health & Fitness", "Wellness", 707)
        de("Health & Fitness", "Accessibility", 707)

        de("Pop Culture", "Characters", 801)
        de("Pop Culture", "Memes & Trends", 105)
        de("Pop Culture", "Other Pop Culture", 105)

        // Las de MakerLab de MakerWorld son para lo hecho con sus herramientas: esto va a Arte
        for (sub in listOf("Make My Phone Case", "Make My Light", "AI Modeling")) de("Generative 3D Model", sub, 105)
        de("Creative Kit Model", "", 307)
    }

    /**
     * La categoría de MakerWorld para una de MakerOnline (["Toys&Games", "Action Figures"]).
     * Sin categoría se usa la de por defecto de MakerOnline (Toys&Games > Action Figures).
     */
    @JvmStatic
    fun paraMakerOnline(categoriaMakerOnline: List<String>?): Eleccion {
        val cat = if (categoriaMakerOnline.isNullOrEmpty() || categoriaMakerOnline.all { it.isBlank() }) {
            CatalogoMakerOnline.CATEGORIA_POR_DEFECTO
        } else categoriaMakerOnline
        val id = EQUIVALENCIAS[cat[0] to (cat.getOrNull(1) ?: "")] ?: return POR_DEFECTO
        return porId(id) ?: POR_DEFECTO
    }

    /** Una licencia: el valor de la opción en la web (`data-value`) y su texto. */
    data class Licencia(val valor: String, val texto: String)

    /** Las opciones del desplegable de licencia, en el orden de la web. */
    @JvmField
    val LICENCIAS: List<Licencia> = listOf(
        Licencia("CC0", "This user content is licensed under Creative Commons Public Domain"),
        Licencia("BY", "This user content is licensed under Creative Commons Attribution"),
        Licencia("BY-SA", "This user content is licensed under Creative Commons Attribution-Share Alike"),
        Licencia("BY-ND", "This user content is licensed under Creative Commons Attribution-NoDerivatives"),
        Licencia("BY-NC", "This user content is licensed under Creative Commons Attribution-Noncommercial"),
        Licencia("BY-NC-SA", "This user content is licensed under Creative Commons Attribution-Noncommercial-Share Alike"),
        Licencia("BY-NC-ND", "This user content is licensed under Creative Commons Attribution-Noncommercial-NoDerivatives"),
        Licencia("Standard Digital File License", "Standard Digital File License"),
        Licencia("MakerWorld Exclusive License", "MakerWorld Exclusive License"),
        Licencia("Standard Digital File License - Community Use", "Standard Digital File License - Community Use"),
        Licencia("Standard Digital File License - Platform Print Only (SDFL-PPO)",
            "Standard Digital File License - Platform Print Only (SDFL-PPO)"),
    )

    /** La licencia que se elige si la de MakerOnline no existe en MakerWorld. */
    const val LICENCIA_POR_DEFECTO = "CC0"

    /**
     * El valor de MakerWorld para una licencia de MakerOnline: "CC BY-SA" → "BY-SA", "CC0" → "CC0",
     * "Standard Digital File License" igual. null si no hay equivalente (entonces se usa [LICENCIA_POR_DEFECTO]).
     */
    @JvmStatic
    fun licenciaDeMakerOnline(licencia: String?): String? {
        val l = CatalogoMakerOnline.normalizarLicencia(licencia)?.trim() ?: return null
        val valor = if (l.startsWith("CC ")) l.removePrefix("CC ").trim() else l
        return LICENCIAS.firstOrNull { it.valor.equals(valor, ignoreCase = true) }?.valor
    }

    /**
     * Límites del formulario (comprobados el 10/11/2026 con sus contadores): nombre 50 caracteres
     * ("0/50"), como mucho 50 etiquetas ("Tags (0 / 50)") de 100 caracteres cada una ("0/100").
     */
    const val MAX_NOMBRE = 50
    const val MAX_ETIQUETAS = 50
    const val MAX_LETRAS_ETIQUETA = 100
    /** Fotos de hasta 16 en "Model Pictures" ("( 0 / 16 )"). */
    const val MAX_FOTOS = 16
}
