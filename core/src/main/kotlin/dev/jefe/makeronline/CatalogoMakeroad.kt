package dev.jefe.makeronline

/**
 * Categorías y límites del formulario de subida de Makeroad, y la categoría que toca a cada una de
 * MakerOnline.
 *
 * Las categorías se eligen por su **nombre tal como sale en la ventana "Clasificación de modelos"**
 * (grupo y opción), con la web en español: así salían el 17/11/2026 en el Chrome de la persona. Si la
 * web saliera en otro idioma, no se encontrarían y el subidor lo diría (no elige otra al azar).
 *
 * Grupos y opciones vistos el 17/11/2026 (los de impresoras y técnicas no se usan):
 *  - Hogar & Vida: Decoración, Organización, Artículos Diarios, Cocina & Baño, Material de oficina,
 *    Productos para mascotas, Light fixture, Otros
 *  - Arte y Diseño: IP De Juguetes De Moda, Arte en relieve, Llaveros, Original decoration, Obra de arte, Otros
 *  - Aficiones y Bricolaje: Modelos de control remoto, Modelos De Vehículos, Modelos Militares, Modelos de
 *    Avión, Robots y Mechas, Modelos en miniatura, Accesorios digitales, Cosplay Props, DIY, Fuse Bead Supplies, Otros
 *  - Moda Wear: Acc. Cara, Joyería, Bolsos, Zapatos, Otros
 *  - Juegos y Juguetes: Juegos de Mesa, Accesorios de Juego, Figuras, Juguetes Decor, Juguetes para niños,
 *    Fidget Toys, Jigsaw Puzzle, Otros
 *  - Herramientas: …, Herramientas Hogar, Almacenaje, Herramientas de Jardín, Picnic y Camping, Papelería & Docencia, …
 *  - (además: Filamentos FDM, Ámbitos Profesionales, Difficulty Levels, Printing Techniques)
 */
object CatalogoMakeroad {

    /** Una categoría de Makeroad: el grupo y la opción, con su texto en la web. */
    data class Categoria(val grupo: String, val opcion: String)

    /** Límites del formulario (17/11/2026): título de 60 caracteres y de 3 a 10 fotos. */
    const val MAX_TITULO = 60
    const val MIN_FOTOS = 3
    const val MAX_FOTOS = 10
    /** Sin comprobar el máximo de la web: se ponen como mucho 10. */
    const val MAX_ETIQUETAS = 10

    private const val JUGUETES = "Juegos y Juguetes"
    private const val HOGAR = "Hogar & Vida"
    private const val ARTE = "Arte y Diseño"
    private const val AFICIONES = "Aficiones y Bricolaje"
    private const val MODA = "Moda Wear"
    private const val HERRAMIENTAS = "Herramientas"

    /** La de la categoría por defecto de MakerOnline (Toys&Games > Action Figures). */
    @JvmField
    val POR_DEFECTO = Categoria(JUGUETES, "Figuras")

    /** Categoría de MakerOnline (primer nivel, segundo nivel) → la de Makeroad. "" = cualquier otra del grupo. */
    private val EQUIVALENCIAS: Map<Pair<String, String>, Categoria> = mapOf(
        ("Toys&Games" to "Action Figures") to Categoria(JUGUETES, "Figuras"),
        ("Toys&Games" to "Characters") to Categoria(JUGUETES, "Figuras"),
        ("Toys&Games" to "Board Games") to Categoria(JUGUETES, "Juegos de Mesa"),
        ("Toys&Games" to "Puzzle") to Categoria(JUGUETES, "Jigsaw Puzzle"),
        ("Toys&Games" to "") to Categoria(JUGUETES, "Otros"),
        ("Household" to "Home Decor") to Categoria(HOGAR, "Decoración"),
        ("Household" to "Kitchen") to Categoria(HOGAR, "Cocina & Baño"),
        ("Household" to "Office") to Categoria(HOGAR, "Material de oficina"),
        ("Household" to "Pets") to Categoria(HOGAR, "Productos para mascotas"),
        ("Household" to "") to Categoria(HOGAR, "Otros"),
        ("Art" to "Sculptures & Statues") to Categoria(ARTE, "Obra de arte"),
        ("Art" to "") to Categoria(ARTE, "Otros"),
        ("Fashion" to "Jewelry") to Categoria(MODA, "Joyería"),
        ("Fashion" to "Bags & Purses") to Categoria(MODA, "Bolsos"),
        ("Fashion" to "") to Categoria(MODA, "Otros"),
        ("Hobby&DIY" to "RC") to Categoria(AFICIONES, "Modelos de control remoto"),
        ("Hobby&DIY" to "Automotive") to Categoria(AFICIONES, "Modelos De Vehículos"),
        ("Hobby&DIY" to "Robotics") to Categoria(AFICIONES, "Robots y Mechas"),
        ("Hobby&DIY" to "") to Categoria(AFICIONES, "Otros"),
        ("Costumes & Cosplay" to "") to Categoria(AFICIONES, "Cosplay Props"),
        ("Miniatures" to "") to Categoria(AFICIONES, "Modelos en miniatura"),
        ("Tools" to "Organizers") to Categoria(HERRAMIENTAS, "Almacenaje"),
        ("Tools" to "") to Categoria(HERRAMIENTAS, "Herramientas Hogar"),
    )

    /** La categoría de Makeroad para una de MakerOnline (sin categoría o desconocida: [POR_DEFECTO]). */
    @JvmStatic
    fun paraMakerOnline(categoriaMakerOnline: List<String>?): Categoria {
        val cat = if (categoriaMakerOnline.isNullOrEmpty()) CatalogoMakerOnline.CATEGORIA_POR_DEFECTO else categoriaMakerOnline
        return EQUIVALENCIAS[cat[0] to (cat.getOrNull(1) ?: "")] ?: EQUIVALENCIAS[cat[0] to ""] ?: POR_DEFECTO
    }
}
