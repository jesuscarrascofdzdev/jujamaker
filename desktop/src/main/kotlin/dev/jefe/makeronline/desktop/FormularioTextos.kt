package dev.jefe.makeronline.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jefe.makeronline.CatalogoMakerOnline
import dev.jefe.makeronline.ModelMeta

/**
 * Lo que se escribe en la ventana Descripción (título, etiquetas, licencia, categoría, tipo y
 * descripción). La usan "Agregar descripción" de la lista y la ventana Agregar modelo.
 */
class FormularioTextos {
    var titulo by mutableStateOf("")
    /** Separadas por comas o una por línea. */
    var etiquetas by mutableStateOf("")
    var descripcion by mutableStateOf("")
    var licencia by mutableStateOf<String?>(CatalogoMakerOnline.LICENCIA_POR_DEFECTO)
    var categoria by mutableStateOf<String?>(null)
        private set
    var subcategoria by mutableStateOf<String?>(null)
    var tipo by mutableStateOf<String?>(CatalogoMakerOnline.TIPO_POR_DEFECTO)

    /** Rellena el formulario con lo que ya tiene la carpeta (null = vacío, con categoría, licencia y tipo por defecto). */
    fun cargar(m: ModelMeta?) {
        titulo = m?.title().orEmpty()
        etiquetas = m?.tags().orEmpty().joinToString(", ")
        descripcion = m?.description().orEmpty()
        licencia = CatalogoMakerOnline.normalizarLicencia(m?.license()?.ifBlank { null })
            ?: CatalogoMakerOnline.LICENCIA_POR_DEFECTO
        // Sin categoría (o solo la de ejemplo de la plantilla): la de por defecto, como al subir
        val cat = if (m == null || m.sinCategoria()) CatalogoMakerOnline.CATEGORIA_POR_DEFECTO else m.category()
        categoria = cat.getOrNull(0)?.ifBlank { null }
        subcategoria = cat.getOrNull(1)?.ifBlank { null }
        tipo = m?.printType()?.ifBlank { null } ?: CatalogoMakerOnline.TIPO_POR_DEFECTO
    }

    /** Al cambiar de categoría, la subcategoría se borra si no es de la nueva. */
    fun elegirCategoria(nueva: String) {
        if (nueva != categoria) {
            categoria = nueva
            if (subcategoria !in CatalogoMakerOnline.CATEGORIAS[nueva].orEmpty()) subcategoria = null
        }
    }

    /** Los textos tal como están, para guardarlos (aunque estén incompletos). */
    fun aMeta(): ModelMeta = ModelMeta(
        titulo.trim(),
        descripcion.trimEnd(),
        etiquetas.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
        listOfNotNull(categoria, subcategoria),
        licencia,
        tipo,
    )

    /** Lo que falta para poder subirlo, con nombres para la persona ("título", "categoría"…). */
    fun faltan(): List<String> {
        val m = aMeta()
        return buildList {
            if (m.title().isBlank()) add(Textos.CAMPO_TITULO.lowercase())
            if (m.tags().isEmpty()) add(Textos.CAMPO_ETIQUETAS.lowercase())
            if (categoria == null) add(Textos.CAMPO_CATEGORIA.lowercase())
            else if (!CatalogoMakerOnline.categoriaCompleta(listOfNotNull(categoria, subcategoria))) {
                add(Textos.CAMPO_SUBCATEGORIA.lowercase())
            }
            if (m.description().isBlank()) add(Textos.CAMPO_DESCRIPCION.lowercase())
        }
    }

    /** "Categoría - Subcategoría", o null si no hay ninguna elegida. */
    val categoriaElegida: String?
        get() = listOfNotNull(categoria, subcategoria).joinToString(" - ").ifEmpty { null }

    // Opciones de cada desplegable: las de MakerOnline más la que ya tuviera la carpeta (para no perderla)
    fun licencias(): List<String> = (CatalogoMakerOnline.LICENCIAS + listOfNotNull(licencia)).distinct()
    fun categorias(): List<String> = (CatalogoMakerOnline.CATEGORIAS.keys + listOfNotNull(categoria)).distinct()
    fun subcategorias(): List<String> =
        (CatalogoMakerOnline.CATEGORIAS[categoria].orEmpty() + listOfNotNull(subcategoria)).distinct()
    fun tipos(): List<String> = (CatalogoMakerOnline.TIPOS + listOfNotNull(tipo)).distinct()
}
