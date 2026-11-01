package dev.jefe.makeronline.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.jefe.makeronline.CatalogoMakerOnline
import dev.jefe.makeronline.InstruccionesIA
import dev.jefe.makeronline.ModelFolder
import dev.jefe.makeronline.ModelMeta
import dev.jefe.makeronline.Rutas
import dev.jefe.makeronline.TextosCarpeta
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lo que puede hacer una IA con la app: la misma lista sirve para el conector (Claude Desktop o
 * Claude Code, ver [ServidorMcp]) y, más adelante, para el chat de la columna de la izquierda.
 * Cada acción hace lo mismo que los botones, con las mismas comprobaciones.
 *
 * Fase 1a: carpetas de pendientes y sus textos (sin Chrome).
 */
class Acciones(private val rutas: Rutas) {

    /** Lo que devuelve una acción: textos e imágenes, y si ha sido un error. */
    class Respuesta(val partes: List<Parte>, val error: Boolean = false) {
        companion object {
            fun texto(t: String) = Respuesta(listOf(Parte.Texto(t)))
            fun error(t: String) = Respuesta(listOf(Parte.Texto(t)), error = true)
        }
    }

    sealed interface Parte {
        data class Texto(val texto: String) : Parte
        class Imagen(val datos: ByteArray, val tipo: String) : Parte
    }

    /**
     * Una acción: su nombre, la explicación para la IA y sus parámetros (JSON Schema).
     */
    class Accion(
        val nombre: String,
        val descripcion: String,
        val parametros: ObjectNode,
        val hacer: (JsonNode) -> Respuesta,
    )

    private val json = ObjectMapper()

    /** Lo que se le dice a la IA al conectarse: qué es esto y cómo usarlo. */
    val instrucciones: String = """
        Herramientas de JujaMaker, una app de escritorio (no oficial) para subir y descargar modelos 3D
        de MakerOnline (makeronline.com). Trabajan con las carpetas de modelos pendientes de subir: cada
        carpeta tiene un .3mf (o .stl), una imagen y los textos (título, descripción, etiquetas,
        categoría, licencia y tipo de impresión).
        - Para escribir la descripción de un modelo: mira su foto (ver_foto) y sus textos (ver_textos),
          escríbela y guárdala con poner_textos. Escribe en inglés salvo que la persona pida otro idioma.
        - Respeta los límites de MakerOnline: título de ${InstruccionesIA.MAX_TITULO} caracteres, hasta
          ${InstruccionesIA.MAX_ETIQUETAS} etiquetas y descripción de ${InstruccionesIA.MAX_DESCRIPCION} caracteres.
        - La categoría tiene que ser una de opciones_formulario, escrita igual. No inventes medidas,
          materiales ni datos técnicos que no te hayan dado.
        - Contesta a la persona en el idioma en que te escriba.
    """.trimIndent()

    val lista: List<Accion> = listOf(
        Accion(
            "listar_pendientes",
            "Lista las carpetas de modelos pendientes de subir, con su título y categoría y lo que les falta para poder subirse.",
            esquema(),
        ) { listarPendientes() },
        Accion(
            "ver_textos",
            "Enseña los textos que tiene ahora una carpeta de pendientes (título, categoría, etiquetas, licencia, tipo y descripción) y sus archivos.",
            esquema("carpeta" to texto("Nombre de la carpeta, como sale en listar_pendientes"), requeridos = listOf("carpeta")),
        ) { verTextos(it.path("carpeta").asText()) },
        Accion(
            "ver_foto",
            "Devuelve la foto (portada) de una carpeta de pendientes, para ver qué modelo es y describirlo.",
            esquema("carpeta" to texto("Nombre de la carpeta, como sale en listar_pendientes"), requeridos = listOf("carpeta")),
        ) { verFoto(it.path("carpeta").asText()) },
        Accion(
            "poner_textos",
            "Guarda los textos de una carpeta de pendientes. Solo cambia lo que se pase; lo demás se queda como estaba. " +
                "Devuelve lo que conviene revisar y si la carpeta ya está lista para subir.",
            esquema(
                "carpeta" to texto("Nombre de la carpeta, como sale en listar_pendientes"),
                "titulo" to texto("Título (como mucho ${InstruccionesIA.MAX_TITULO} caracteres)"),
                "descripcion" to texto("Descripción completa; puede tener varios párrafos"),
                "etiquetas" to lista("Etiquetas (hasta ${InstruccionesIA.MAX_ETIQUETAS}), sin #"),
                "categoria" to texto("Categoría exacta de opciones_formulario, como \"Toys&Games > Action Figures\""),
                "licencia" to texto("Licencia exacta de opciones_formulario (por defecto CC0)"),
                "tipo" to texto("Tipo de impresión: FDM, Resin o Both are fine (por defecto FDM)"),
                requeridos = listOf("carpeta"),
            ),
        ) { ponerTextos(it) },
        Accion(
            "opciones_formulario",
            "Las categorías, licencias y tipos de impresión que admite MakerOnline, escritos exactamente como hay que ponerlos.",
            esquema(),
        ) { opciones() },
    )

    /** Ejecuta una acción por su nombre; los fallos se devuelven como respuesta de error, no como excepción. */
    fun ejecutar(nombre: String, argumentos: JsonNode?): Respuesta {
        val accion = lista.firstOrNull { it.nombre == nombre } ?: return Respuesta.error("No existe la acción \"$nombre\".")
        return try {
            accion.hacer(argumentos ?: json.createObjectNode())
        } catch (e: Exception) {
            Respuesta.error("No se ha podido hacer: " + (e.message?.lineSequence()?.firstOrNull() ?: e.toString()))
        }
    }

    // ------------------------------------------------------------ las acciones

    private fun listarPendientes(): Respuesta {
        val carpetas = carpetas()
        if (carpetas.isEmpty()) return Respuesta.texto("No hay ninguna carpeta en pendientes.")
        val lineas = carpetas.map { dir ->
            val nombre = dir.fileName.toString()
            try {
                val m = ModelFolder.load(dir)
                val meta = m.meta()
                "- $nombre: lista para subir · \"${meta.title()}\" · ${meta.categoryOrDefault().joinToString(" > ")}" +
                    (if (meta.sinCategoria()) " (por defecto)" else "")
            } catch (e: IllegalStateException) {
                "- $nombre: no se puede subir todavía: " + (e.message ?: "").lines().filter { it.isNotBlank() }.joinToString("; ")
            }
        }
        return Respuesta.texto("Carpetas en pendientes (${carpetas.size}):\n" + lineas.joinToString("\n"))
    }

    private fun verTextos(nombre: String): Respuesta {
        val dir = carpeta(nombre)
        val l = TextosCarpeta.leer(dir)
        val texto = buildString {
            append("Carpeta ${dir.fileName}\n")
            append("Archivos: ").append(archivos(dir).joinToString(", ") { it.fileName.toString() }.ifEmpty { "(ninguno)" }).append('\n')
            l.problema?.let { append("Aviso: ").append(it).append('\n') }
            val m = l.meta
            if (m == null) {
                append("Todavía no tiene textos.")
            } else {
                append("Título: ").append(m.title().orEmpty()).append('\n')
                append("Categoría: ").append(m.category().orEmpty().joinToString(" > ").ifEmpty { "(sin poner: se usará ${CatalogoMakerOnline.CATEGORIA_POR_DEFECTO.joinToString(" > ")})" }).append('\n')
                append("Etiquetas: ").append(m.tags().orEmpty().joinToString(", ")).append('\n')
                append("Licencia: ").append(m.licenseOrDefault()).append('\n')
                append("Tipo: ").append(m.printTypeOrDefault()).append('\n')
                append("Descripción:\n").append(m.description().orEmpty())
            }
        }
        return Respuesta.texto(texto)
    }

    private fun verFoto(nombre: String): Respuesta {
        val dir = carpeta(nombre)
        val foto = ModelFolder.imagenesDe(dir).firstOrNull() ?: return Respuesta.error("La carpeta ${dir.fileName} no tiene ninguna imagen.")
        val datos = reducir(foto) ?: return Respuesta.error("No se puede leer la imagen ${foto.fileName}.")
        return Respuesta(listOf(Parte.Texto("Foto de ${dir.fileName} (${foto.fileName}):"), Parte.Imagen(datos, "image/jpeg")))
    }

    private fun ponerTextos(a: JsonNode): Respuesta {
        val dir = carpeta(a.path("carpeta").asText())
        val l = TextosCarpeta.leer(dir)
        if (l.problema != null && l.archivo == null) return Respuesta.error(l.problema!!)
        val antes = l.meta
        fun cadena(campo: String): String? = a.get(campo)?.takeIf { it.isTextual }?.asText()?.trim()?.ifEmpty { null }

        val etiquetas = a.get("etiquetas")?.let { e ->
            when {
                e.isArray -> e.mapNotNull { it.asText().trim().trim('#').trim().ifEmpty { null } }
                e.isTextual -> e.asText().split(',').map { it.trim().trim('#').trim() }.filter { it.isNotEmpty() }
                else -> null
            }
        }?.distinctBy { it.lowercase() }
        val categoria = cadena("categoria")?.let { InstruccionesIA.ajustarCategoria(it.split('>').map(String::trim)) }
        val avisos = ArrayList<String>()
        if (etiquetas != null && etiquetas.size > InstruccionesIA.MAX_ETIQUETAS) {
            avisos += "había ${etiquetas.size} etiquetas y MakerOnline admite ${InstruccionesIA.MAX_ETIQUETAS}: se han guardado las ${InstruccionesIA.MAX_ETIQUETAS} primeras"
        }
        val meta = ModelMeta(
            cadena("titulo") ?: antes?.title(),
            a.get("descripcion")?.takeIf { it.isTextual }?.asText()?.trim()?.ifEmpty { null } ?: antes?.description(),
            etiquetas?.take(InstruccionesIA.MAX_ETIQUETAS) ?: antes?.tags().orEmpty(),
            categoria ?: antes?.category(),
            CatalogoMakerOnline.normalizarLicencia(cadena("licencia")) ?: antes?.license(),
            cadena("tipo") ?: antes?.printType(),
        )
        val destino = TextosCarpeta.guardar(dir, l.archivo, meta)
        avisos += InstruccionesIA.revisar(meta)
        val lista = try {
            ModelFolder.load(dir)
            "La carpeta ya está lista para subir."
        } catch (e: IllegalStateException) {
            "Todavía no se puede subir: " + (e.message ?: "").lines().filter { it.isNotBlank() }.joinToString("; ")
        }
        return Respuesta.texto(
            "Guardado en ${destino.fileName} (carpeta ${dir.fileName}).\n" +
                (if (avisos.isEmpty()) "" else "A revisar:\n" + avisos.joinToString("\n") { "- $it" } + "\n") +
                lista,
        )
    }

    private fun opciones(): Respuesta = Respuesta.texto(
        "Categorías (escríbelas igual):\n" + InstruccionesIA.categorias().joinToString("\n") { "- $it" } +
            "\n\nLicencias: " + CatalogoMakerOnline.LICENCIAS.joinToString(", ") +
            "\nTipos de impresión: " + CatalogoMakerOnline.TIPOS.joinToString(", ") +
            "\nSi no se pone, se usa: ${CatalogoMakerOnline.CATEGORIA_POR_DEFECTO.joinToString(" > ")}, " +
            "${CatalogoMakerOnline.LICENCIA_POR_DEFECTO} y ${CatalogoMakerOnline.TIPO_POR_DEFECTO}." +
            "\nLímites: título ${InstruccionesIA.MAX_TITULO} caracteres, ${InstruccionesIA.MAX_ETIQUETAS} etiquetas, " +
            "descripción ${InstruccionesIA.MAX_DESCRIPCION} caracteres.",
    )

    // ------------------------------------------------------------ ayudas

    private fun carpetas(): List<Path> {
        val dir = rutas.pendientes
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { Files.isDirectory(it) }.sorted().toList() }
    }

    /** La carpeta de pendientes con ese nombre (sin distinguir mayúsculas; vale un trozo si solo hay una que lo tenga). */
    private fun carpeta(nombre: String): Path {
        val buscado = nombre.trim()
        require(buscado.isNotEmpty()) { "Falta el nombre de la carpeta." }
        val todas = carpetas()
        todas.firstOrNull { it.fileName.toString() == buscado }?.let { return it }
        todas.firstOrNull { it.fileName.toString().equals(buscado, ignoreCase = true) }?.let { return it }
        val parecidas = todas.filter { it.fileName.toString().contains(buscado, ignoreCase = true) }
        if (parecidas.size == 1) return parecidas[0]
        val nombres = todas.joinToString(", ") { it.fileName.toString() }.ifEmpty { "ninguna" }
        throw IllegalArgumentException(
            if (parecidas.size > 1) "Hay varias carpetas que contienen \"$buscado\": ${parecidas.joinToString(", ") { it.fileName.toString() }}."
            else "No hay ninguna carpeta \"$buscado\" en pendientes. Hay: $nombres.",
        )
    }

    private fun archivos(dir: Path): List<Path> =
        Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) }.sorted().toList() }

    /** La imagen en JPEG, con el lado mayor de 1024 píxeles como mucho (suficiente para la IA y pesa poco). */
    private fun reducir(foto: Path): ByteArray? {
        val original = ImageIO.read(foto.toFile()) ?: return null
        val escala = minOf(1.0, LADO_MAXIMO.toDouble() / max(original.width, original.height))
        val ancho = (original.width * escala).roundToInt().coerceAtLeast(1)
        val alto = (original.height * escala).roundToInt().coerceAtLeast(1)
        val imagen = BufferedImage(ancho, alto, BufferedImage.TYPE_INT_RGB)
        val g = imagen.createGraphics()
        try {
            g.color = java.awt.Color.WHITE // fondo blanco para las PNG con transparencia
            g.fillRect(0, 0, ancho, alto)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.drawImage(original, 0, 0, ancho, alto, null)
        } finally {
            g.dispose()
        }
        val salida = ByteArrayOutputStream()
        ImageIO.write(imagen, "jpg", salida)
        return salida.toByteArray()
    }

    // ------------------------------------------------------------ esquemas de parámetros (JSON Schema)

    private fun esquema(vararg campos: Pair<String, ObjectNode>, requeridos: List<String> = emptyList()): ObjectNode {
        val e = json.createObjectNode().put("type", "object")
        val props = e.putObject("properties")
        for ((nombre, tipo) in campos) props.set<ObjectNode>(nombre, tipo)
        if (requeridos.isNotEmpty()) e.putArray("required").apply { requeridos.forEach { add(it) } }
        return e
    }

    private fun texto(descripcion: String): ObjectNode =
        json.createObjectNode().put("type", "string").put("description", descripcion)

    private fun lista(descripcion: String): ObjectNode =
        json.createObjectNode().put("type", "array").put("description", descripcion)
            .also { it.putObject("items").put("type", "string") }

    private companion object {
        const val LADO_MAXIMO = 1024
    }
}
