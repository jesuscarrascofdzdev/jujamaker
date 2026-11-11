package dev.jefe.makeronline

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.json.JsonReadFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale

/**
 * La lista de usuarios de Printables de los que se descarga (usuarios-printables.json). Va aparte de
 * las de MakerOnline y Creality: son webs y cuentas distintas. Es igual que ListaUsuariosCreality,
 * pero cada usuario se reconoce por su "handle", lo que va tras la @ en la dirección de su perfil
 * (https://www.printables.com/@VC_Design; la web lo acepta tal cual para pedir sus modelos, comprobado
 * el 11/11/2026):
 *  - "VC Design · https://www.printables.com/@VC_Design"
 *  - "https://www.printables.com/@VC_Design/models" (la dirección tal cual)
 *  - "@VC_Design" (solo el handle)
 *
 * Es un array JSON de textos (admite comentarios // y una coma de más al final).
 */
object ListaUsuariosPrintables {

    /** Lo leído: las entradas (sin vacías ni usuarios repetidos), el error si lo hay y si el archivo existe. */
    data class Lectura(val entradas: List<String>, val error: String?, val existe: Boolean)

    private val JSON_LISTA = JsonMapper.builder()
        .enable(JsonReadFeature.ALLOW_JAVA_COMMENTS, JsonReadFeature.ALLOW_TRAILING_COMMA)
        .build()
    private const val LETRAS = "[A-Za-z0-9_.\\-]+"
    private val EN_URL = Regex("""printables\.com/(?:[a-z]{2}(?:-[a-z]{2})?/)?@($LETRAS)""", RegexOption.IGNORE_CASE)
    private val CON_ARROBA = Regex("""@($LETRAS)""")
    private val SOLO_HANDLE = Regex("""^$LETRAS$""")

    private val CABECERA = """
        |[
        |  // Usuarios de Printables de los que descarga la app (con la extensión de Chrome): uno por
        |  // línea, entre comillas y separados por comas. Cada uno es la dirección de su perfil, con un
        |  // nombre delante si quieres:
        |  //   "VC Design · https://www.printables.com/@VC_Design",
        |  // Las líneas que empiezan por // se ignoran. La app reescribe este archivo al añadir o quitar.
        |""".trimMargin()

    /** El handle de una entrada (sin la @), o null si no lo lleva. */
    @JvmStatic
    fun handleDe(entrada: String): String? {
        EN_URL.find(entrada)?.let { return it.groupValues[1] }
        CON_ARROBA.find(entrada)?.let { return it.groupValues[1] }
        val ultimo = entrada.substringAfterLast('·').trim()
        return ultimo.takeIf { SOLO_HANDLE.matches(it) }
    }

    /** El handle para comparar (Printables no distingue mayúsculas en las direcciones). */
    @JvmStatic
    fun claveDe(entrada: String): String? = handleDe(entrada)?.lowercase(Locale.ROOT)

    /** El nombre que lleva delante una entrada, o null si solo es la dirección o el handle. */
    @JvmStatic
    fun nombreDe(entrada: String): String? {
        if (!entrada.contains('·')) return null
        return entrada.substringBefore('·').trim().ifEmpty { null }
    }

    /** La dirección del perfil de un usuario. */
    @JvmStatic
    fun perfil(handle: String): String = "https://www.printables.com/@$handle"

    /** Cómo se guarda un usuario: "Nombre · dirección del perfil" (sin nombre, solo la dirección). */
    @JvmStatic
    fun entrada(nombre: String?, handle: String): String =
        if (nombre.isNullOrBlank()) perfil(handle) else "${nombre.trim()} · ${perfil(handle)}"

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
            val clave = claveDe(s) ?: s.lowercase(Locale.ROOT)
            if (s.isNotEmpty() && vistos.add(clave)) entradas += s
        }
        return Lectura(entradas, null, existe = true)
    }

    /**
     * Añade un usuario a partir de lo que escriba la persona (la dirección de su perfil o su @handle,
     * con un nombre delante si quiere). Devuelve la entrada guardada.
     *
     * @throws AgenteException si no lleva el handle o si ya está en la lista
     */
    @JvmStatic
    fun agregar(archivo: Path, texto: String): String {
        val handle = handleDe(texto) ?: throw AgenteException(
            "No encuentro el usuario. Pega la dirección de su perfil en Printables, " +
                "por ejemplo https://www.printables.com/@VC_Design"
        )
        val actuales = paraEditar(archivo)
        if (actuales.any { claveDe(it) == handle.lowercase(Locale.ROOT) }) throw AgenteException("Ese usuario ya está en la lista.")
        val nueva = entrada(nombreDe(texto), handle)
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
     * Pone el nombre de verdad a una entrada que no lo tenía (lo sabe la extensión al leer sus
     * modelos). Devuelve la entrada nueva, o la misma si no hay nada que cambiar o no se puede guardar.
     */
    @JvmStatic
    fun ponerNombre(archivo: Path, entrada: String, nombre: String): String {
        val handle = handleDe(entrada) ?: return entrada
        if (nombre.isBlank() || nombreDe(entrada) != null) return entrada
        return try {
            val actuales = paraEditar(archivo)
            if (entrada !in actuales) return entrada
            val nueva = entrada(nombre, handle)
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
