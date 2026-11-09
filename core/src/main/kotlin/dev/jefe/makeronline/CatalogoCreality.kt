package dev.jefe.makeronline

/**
 * Las categorías de Creality Cloud y cómo se traduce a ellas cada categoría de MakerOnline.
 *
 * Los textos de cada modelo llevan la categoría de MakerOnline (ver CatalogoMakerOnline); para
 * Creality se busca aquí la equivalente. Así no hay que escribir nada nuevo en los textos.
 *
 * Comprobado el 09/11/2026 en la web (`__NUXT__.data['categoryList-model']`, igual que la respuesta
 * de `api/cxy/v2/common/categoryList`). Cada categoría tiene su id y su nombre en inglés y en
 * español: la web se ve en uno u otro idioma según lo que recuerde, y en el desplegable sale ese nombre.
 * Si Creality cambia sus categorías, se vuelve a mirar ahí y se actualiza este archivo.
 */
object CatalogoCreality {

    /** Una categoría de Creality: su id y su nombre en inglés y en español. */
    data class Categoria(val id: String, val en: String, val es: String) {
        /** El nombre tal como sale en la web, según su idioma. */
        fun nombre(espanol: Boolean): String = if (espanol) es else en
    }

    /** Una categoría de primer nivel con las suyas de segundo nivel. */
    data class Grupo(val categoria: Categoria, val subcategorias: List<Categoria>)

    @JvmField
    val CATEGORIAS: List<Grupo> = listOf(
        Grupo(Categoria("1731", "3D Printers", "Impresoras 3D"), listOf(
            Categoria("1316", "3D Printer Parts", "Piezas de impresora 3D"),
            Categoria("1904", "3D Printer Accessories", "Accesorios de impresora 3D"),
            Categoria("1645", "Test Models", "Modelos de prueba"),
            Categoria("6006", "Other", "Otro"),
        )),
        Grupo(Categoria("1670", "Art & Design", "Arte y diseño"), listOf(
            Categoria("1662", "Digital Art", "Arte digital"),
            Categoria("1584", "Sculptures & Artworks", "Esculturas y obras de arte"),
            Categoria("1341", "Badges & Coins", "Insignias y monedas"),
            Categoria("1997", "Industrial Design & Prototypes", "Diseño industrial y prototipos"),
            Categoria("6005", "Other", "Otro"),
        )),
        Grupo(Categoria("1809", "Toys & Games", "Juguetes y juegos"), listOf(
            Categoria("1575", "Board Games & Card Games", "Juegos de mesa y de cartas"),
            Categoria("1141", "Construction Toys", "Juguetes de construcción"),
            Categoria("1793", "Game Props", "Accesorios de juego"),
            Categoria("6007", "Other", "Otro"),
        )),
        Grupo(Categoria("1519", "Hobbies & DIY", "Aficiones y bricolaje"), listOf(
            Categoria("1741", "Electronics & RC (Remote Control)", "Electrónica y RC (Radiocontrol)"),
            Categoria("1648", "Robots & Mechs", "Robots y mechas"),
            Categoria("1194", "Drones & Aircraft", "Drones y aeronaves"),
            Categoria("1420", "Sound & Audio Equipment", "Equipos de sonido y audio"),
            Categoria("1246", "Sports & Exercise Equipment", "Equipos deportivos y de ejercicio"),
            Categoria("6004", "Other", "Otro"),
        )),
        Grupo(Categoria("1010", "Household", "Hogar"), listOf(
            Categoria("1150", "Home Decorations & Ornaments", "Decoraciones y ornamentos para el hogar"),
            Categoria("1096", "Lighting & Lamps", "Iluminación y lámparas"),
            Categoria("1775", "Home Appliance Accessories", "Accesorios para electrodomésticos"),
            Categoria("1671", "Tools & Spare Parts", "Herramientas y repuestos"),
            Categoria("1151", "Pets", "Mascotas"),
            Categoria("6000", "Other", "Otro"),
        )),
        Grupo(Categoria("1175", "Fashion", "Moda"), listOf(
            Categoria("1966", "Cosplay", "Cosplay"),
            Categoria("1693", "Jewelry & Accessories", "Joyería y accesorios"),
            Categoria("1598", "Apparel, Shoes & Hats", "Ropa, zapatos y sombreros"),
            Categoria("1647", "Personal Accessories", "Accesorios personales"),
            Categoria("6002", "Other", "Otro"),
        )),
        Grupo(Categoria("1501", "Education", "Educación"), listOf(
            Categoria("1974", "Stationery & Learning Tools", "Papelería y herramientas de aprendizaje"),
            Categoria("1343", "Educational Aids", "Ayudas educativas"),
            Categoria("6003", "Other", "Otro"),
        )),
        Grupo(Categoria("1952", "Miniatures", "Miniaturas"), listOf(
            Categoria("1025", "Characters & Creatures", "Personajes y criaturas"),
            Categoria("1888", "Miniature Games & Accessories", "Juegos de miniaturas y accesorios"),
            Categoria("1846", "Props & Terrain", "Accesorios y terrenos"),
            Categoria("1982", "Vehicles & Machinery", "Vehículos y maquinaria"),
            Categoria("6008", "Other", "Otro"),
        )),
        Grupo(Categoria("1160", "Medical & Health", "Medicina y salud"), listOf(
            Categoria("1192", "Medical & Health Equipment", "Equipos médicos y de salud"),
            Categoria("1765", "Personal Care Devices", "Dispositivos de cuidado personal"),
            Categoria("6001", "Other", "Otro"),
        )),
        // "MakeNow" (MagicRelief, CubeMe, SnapForm, SignForge) es de las herramientas de Creality: no se usa
    )

    /** La categoría de Creality: el grupo (primer nivel) y la subcategoría (segundo nivel). */
    data class Eleccion(val grupo: Categoria, val sub: Categoria) {
        override fun toString() = "${grupo.en} > ${sub.en}"
    }

    /** Busca por nombre en inglés: "Toys & Games" y "Other". */
    private fun elegir(grupo: String, sub: String): Eleccion {
        val g = CATEGORIAS.first { it.categoria.en == grupo }
        return Eleccion(g.categoria, g.subcategorias.first { it.en == sub })
    }

    /** Para lo que no está en la tabla (no debería pasar): Toys & Games > Other. */
    @JvmField
    val POR_DEFECTO: Eleccion = elegir("Toys & Games", "Other")

    /**
     * MakerOnline (primer nivel > segundo nivel) → Creality (primer nivel > segundo nivel), en inglés.
     * Propuesta del 09/11/2026: Jefe la revisa y se corrige aquí lo que no encaje.
     */
    private val EQUIVALENCIAS: Map<Pair<String, String>, Pair<String, String>> = buildMap {
        fun de(mo: String, subMo: String, cr: String, subCr: String) = put(mo to subMo, cr to subCr)

        de("3D Printer", "3D Printer Parts & Upgrades", "3D Printers", "3D Printer Parts")
        de("3D Printer", "3D Printer Accessories", "3D Printers", "3D Printer Accessories")
        de("3D Printer", "Test Models", "3D Printers", "Test Models")

        de("Household", "Home Decor", "Household", "Home Decorations & Ornaments")
        de("Household", "Garden", "Household", "Other")
        de("Household", "Kitchen", "Household", "Other")
        de("Household", "Office", "Household", "Other")
        de("Household", "Festivities", "Household", "Home Decorations & Ornaments")
        de("Household", "Living Room", "Household", "Home Decorations & Ornaments")
        de("Household", "Pets", "Household", "Pets")
        de("Household", "Other Household", "Household", "Other")

        de("Hobby&DIY", "Automotive", "Hobbies & DIY", "Other")
        de("Hobby&DIY", "Electronics", "Hobbies & DIY", "Electronics & RC (Remote Control)")
        de("Hobby&DIY", "Music", "Hobbies & DIY", "Sound & Audio Equipment")
        de("Hobby&DIY", "Sports&Outdoor", "Hobbies & DIY", "Sports & Exercise Equipment")
        de("Hobby&DIY", "RC", "Hobbies & DIY", "Electronics & RC (Remote Control)")
        de("Hobby&DIY", "Robotics", "Hobbies & DIY", "Robots & Mechs")
        de("Hobby&DIY", "Mechanical Parts", "Household", "Tools & Spare Parts")
        de("Hobby&DIY", "Other Hobby&DIY", "Hobbies & DIY", "Other")

        de("Tools", "Gadgets", "Household", "Tools & Spare Parts")
        de("Tools", "Organizers", "Household", "Other")
        de("Tools", "Hand Tools", "Household", "Tools & Spare Parts")
        de("Tools", "Machine Tools", "Household", "Tools & Spare Parts")
        de("Tools", "Medical Tools", "Medical & Health", "Medical & Health Equipment")
        de("Tools", "Other Tools", "Household", "Tools & Spare Parts")

        de("Toys&Games", "Action Figures", "Miniatures", "Characters & Creatures")
        de("Toys&Games", "Characters", "Miniatures", "Characters & Creatures")
        de("Toys&Games", "Board Games", "Toys & Games", "Board Games & Card Games")
        de("Toys&Games", "Building Toys", "Toys & Games", "Construction Toys")
        de("Toys&Games", "Outdoor Toys", "Toys & Games", "Other")
        de("Toys&Games", "Puzzle", "Toys & Games", "Board Games & Card Games")
        de("Toys&Games", "Vehicles", "Miniatures", "Vehicles & Machinery")
        de("Toys&Games", "Other Toys&Games", "Toys & Games", "Other")

        de("Art", "2D Plate", "Art & Design", "Digital Art")
        de("Art", "Sculptures & Statues", "Art & Design", "Sculptures & Artworks")
        de("Art", "Animals", "Art & Design", "Sculptures & Artworks")
        de("Art", "Signs & Logos", "Art & Design", "Other")
        de("Art", "Badges", "Art & Design", "Badges & Coins")
        de("Art", "Other Art", "Art & Design", "Other")

        de("Fashion", "Accessories", "Fashion", "Personal Accessories")
        de("Fashion", "Bags & Purses", "Fashion", "Personal Accessories")
        de("Fashion", "Clothing", "Fashion", "Apparel, Shoes & Hats")
        de("Fashion", "Jewelry", "Fashion", "Jewelry & Accessories")
        de("Fashion", "Other Fashion", "Fashion", "Other")

        for (sub in listOf("Biology", "Chemistry", "Geography", "Engineering", "Physics &Astronomy", "Mathematics")) {
            de("Education", sub, "Education", "Educational Aids")
        }
        de("Education", "Other Education", "Education", "Other")

        for (sub in listOf("Costumes", "Masks", "Props", "Other Costumes & Cosplay")) {
            de("Costumes & Cosplay", sub, "Fashion", "Cosplay")
        }

        de("Miniatures", "Characters", "Miniatures", "Characters & Creatures")
        de("Miniatures", "Architecture", "Miniatures", "Props & Terrain")
        de("Miniatures", "Creatures", "Miniatures", "Characters & Creatures")
        de("Miniatures", "Gaming Accessories", "Miniatures", "Miniature Games & Accessories")
        de("Miniatures", "Fantasy&Sci-Fi", "Miniatures", "Characters & Creatures")
        de("Miniatures", "Other Miniatures", "Miniatures", "Other")

        de("Health & Fitness", "Wellness", "Medical & Health", "Personal Care Devices")
        de("Health & Fitness", "Accessibility", "Medical & Health", "Medical & Health Equipment")

        de("Pop Culture", "Characters", "Miniatures", "Characters & Creatures")
        de("Pop Culture", "Memes & Trends", "Art & Design", "Other")
        de("Pop Culture", "Other Pop Culture", "Art & Design", "Other")

        for (sub in listOf("Make My Phone Case", "Make My Light", "AI Modeling")) {
            de("Generative 3D Model", sub, "Art & Design", "Digital Art")
        }
        de("Creative Kit Model", "", "Hobbies & DIY", "Other")
    }

    /**
     * La categoría de Creality para una de MakerOnline (["Toys&Games", "Action Figures"]).
     * Sin categoría se usa la de por defecto de MakerOnline (Toys&Games > Action Figures).
     */
    @JvmStatic
    fun paraMakerOnline(categoriaMakerOnline: List<String>?): Eleccion {
        val cat = if (categoriaMakerOnline.isNullOrEmpty()) CatalogoMakerOnline.CATEGORIA_POR_DEFECTO else categoriaMakerOnline
        val clave = cat[0] to (cat.getOrNull(1) ?: "")
        val destino = EQUIVALENCIAS[clave] ?: return POR_DEFECTO
        return elegir(destino.first, destino.second)
    }

    /** La licencia: de momento siempre CC0 (en la web, "Sí" a las tres preguntas; comprobado). */
    const val LICENCIA = "CC0"

    /** Límites del formulario (comprobados): nombre 60 caracteres, etiquetas de 30 caracteres y como mucho 20. */
    const val MAX_NOMBRE = 60
    const val MAX_LETRAS_ETIQUETA = 30
    const val MAX_ETIQUETAS = 20
}
