package dev.jefe.makeronline

/**
 * Categorías y límites del formulario de subida de Makeroad, y la categoría que se pone (todas a
 * Hogar & Vida > Decoración, ver [POR_DEFECTO]).
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

    private const val HOGAR = "Hogar & Vida"

    /**
     * La categoría de todos los modelos en Makeroad: Hogar & Vida > Decoración, a petición de la persona
     * (17/11/2026), como en Printables, donde todos van a la misma.
     */
    @JvmField
    val POR_DEFECTO = Categoria(HOGAR, "Decoración")

    /**
     * La categoría de Makeroad para un modelo: de momento siempre [POR_DEFECTO], sea cual sea la de
     * MakerOnline. Se deja el parámetro por si un día se quiere volver a elegir según ella.
     */
    @Suppress("UNUSED_PARAMETER")
    @JvmStatic
    fun paraMakerOnline(categoriaMakerOnline: List<String>?): Categoria = POR_DEFECTO
}
