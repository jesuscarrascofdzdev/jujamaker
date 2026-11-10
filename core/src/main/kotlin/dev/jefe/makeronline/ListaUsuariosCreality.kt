package dev.jefe.makeronline

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * La lista de usuarios de Creality Cloud de los que se descarga (usuarios-creality.json). Va aparte
 * de la de MakerOnline (usuarios.json): son webs y cuentas distintas.
 *
 * Es un array JSON de textos (admite comentarios // y una coma de más al final). Cada entrada lleva
 * el número del usuario en Creality, que es lo que sale en la dirección de su perfil
 * (https://www.crealitycloud.com/user/8286142150), con un nombre delante si se quiere:
 *  - "3DPrintyi · https://www.crealitycloud.com/user/8286142150"
 *  - "https://www.crealitycloud.com/es/user/8286142150/model" (la dirección tal cual)
 *  - "8286142150" (solo el número)
 */
object ListaUsuariosCreality {

    /** Lo leído: las entradas (sin vacías ni usuarios repetidos), el error si lo hay y si el archivo existe. */
    data class Lectura(val entradas: List<String>, val error: String?, val existe: Boolean)

    private val JSON_LISTA = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build()
    private val EN_URL = Regex("""/user/(\d{4,})""")
    private val SOLO_NUMERO = Regex("""\d{6,}""")

    private val CABECERA = """
        |[
        |  // Usuarios de Creality Cloud de los que descarga la app: uno por línea, entre comillas y
        |  // separados por comas. Cada uno es la dirección de su perfil, con un nombre delante si quieres:
        |  //   "3DPrintyi · https://www.crealitycloud.com/user/8286142150",
        |  // Las líneas que empiezan por // se ignoran. La app reescribe este archivo al añadir o quitar.
        |""".trimMargin()

    /** El número de usuario de una entrada, o null si no lo lleva. */
    @JvmStatic
    fun idDe(entrada: String): Long? {
        EN_URL.find(entrada)?.let { return it.groupValues[1].toLongOrNull() }
        // Sin dirección: vale el último número largo ("Pepe · 8286142150" o solo el número)
        return SOLO_NUMERO.findAll(entrada).lastOrNull()?.value?.toLongOrNull()
    }

    /** El nombre que lleva delante una entrada, o null si solo es la dirección o el número. */
    @JvmStatic
    fun nombreDe(entrada: String): String? {
        val corte = listOf(entrada.indexOf("http"), SOLO_NUMERO.find(entrada)?.range?.first ?: -1)
            .filter { it >= 0 }.minOrNull() ?: return null
        return entrada.substring(0, corte).trim().trimEnd('·', '-', '|', ':', ',').trim().ifEmpty { null }
    }

    /** La dirección del perfil de un usuario. */
    @JvmStatic
    fun perfil(id: Long): String = "https://www.crealitycloud.com/user/$id"

    /** Cómo se guarda un usuario: "Nombre · dirección del perfil" (sin nombre, solo la dirección). */
    @JvmStatic
    fun entrada(nombre: String?, id: Long): String =
        if (nombre.isNullOrBlank()) perfil(id) else "${nombre.trim()} · ${perfil(id)}"

    /** Lee la lista. Si el archivo no existe, la lista está vacía (no es un error). */
    @JvmStatic
    fun leer(archivo: Path): Lectura {
        if (!Files.exists(archivo)) return Lectura(emptyList(), null, existe = false)
        val nombre = archivo.fileName.toString()
        val raiz = try {
            JSON_LISTA.readTree(archivo.toFile())
        } catch (e: JsonProcessingException) {
            val donde = e.location?.let { " (línea ${it.lineNr})" } ?: ""
            return Lectura(emptyList(), "$nombre no es válido$donde: ${e.originalMessage}", existe = true)
        }
        if (raiz == null || !raiz.isArray) {
            return Lectura(emptyList(), "$nombre tiene que ser una lista: [\"usuario1\", \"usuario2\"]", existe = true)
        }
        val vistos = HashSet<String>()
        val entradas = ArrayList<String>()
        for (n in raiz) {
            val s = n.asText("").trim()
            // El mismo usuario dos veces (aunque con otro nombre delante) solo cuenta una
            val clave = idDe(s)?.toString() ?: s.lowercase()
            if (s.isNotEmpty() && vistos.add(clave)) entradas += s
        }
        return Lectura(entradas, null, existe = true)
    }

    /**
     * Añade un usuario a partir de lo que escriba la persona (la dirección de su perfil o su número,
     * con un nombre delante si quiere). Devuelve la entrada guardada.
     *
     * @throws AgenteException si no lleva el número de usuario o si ya está en la lista
     */
    @JvmStatic
    fun agregar(archivo: Path, texto: String): String {
        val id = idDe(texto) ?: throw AgenteException(
            "No encuentro el número del usuario. Pega la dirección de su perfil en Creality Cloud, " +
                "por ejemplo https://www.crealitycloud.com/user/8286142150"
        )
        val actuales = paraEditar(archivo)
        if (actuales.any { idDe(it) == id }) throw AgenteException("Ese usuario ya está en la lista.")
        val nueva = entrada(nombreDe(texto), id)
        guardar(archivo, actuales + nueva)
        return nueva
    }

    /** Quita una entrada de la lista. */
    @JvmStatic
    fun quitar(archivo: Path, entrada: String) {
        val actuales = paraEditar(archivo)
        val quedan = actuales.filterNot { it == entrada }
        if (quedan.size != actuales.size) guardar(archivo, quedan)
    }

    /**
     * Pone el nombre de verdad a una entrada que no lo tenía (lo sabe el descargador al leer sus
     * modelos). Devuelve la entrada nueva, o la misma si no hay nada que cambiar o no se puede guardar.
     */
    @JvmStatic
    fun ponerNombre(archivo: Path, entrada: String, nombre: String): String {
        val id = idDe(entrada) ?: return entrada
        if (nombre.isBlank() || nombreDe(entrada) != null) return entrada
        return try {
            val actuales = paraEditar(archivo)
            val nueva = entrada(nombre, id)
            if (entrada !in actuales) return entrada
            guardar(archivo, actuales.map { if (it == entrada) nueva else it })
            nueva
        } catch (_: Exception) {
            entrada
        }
    }

    private fun guardar(archivo: Path, entradas: List<String>) {
        val texto = buildString {
            append(CABECERA)
            entradas.forEachIndexed { i, e ->
                append("  ").append(JSON_LISTA.writeValueAsString(e))
                if (i < entradas.size - 1) append(',')
                append('\n')
            }
            append("]\n")
        }
        // Se escribe aparte y luego se cambia por el bueno: si algo falla a medias, no se queda cortado
        archivo.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        val temporal = archivo.resolveSibling(archivo.fileName.toString() + ".tmp")
        Files.writeString(temporal, texto, Charsets.UTF_8)
        try {
            Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporal, archivo, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun paraEditar(archivo: Path): List<String> {
        val l = leer(archivo)
        if (l.error != null) {
            throw AgenteException("No toco ${archivo.fileName} porque tiene un error. Arréglalo a mano y pulsa Recargar.\n" + l.error)
        }
        return l.entradas
    }
}
