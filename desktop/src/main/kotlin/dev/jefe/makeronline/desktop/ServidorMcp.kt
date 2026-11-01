package dev.jefe.makeronline.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.jefe.makeronline.Rutas
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.util.Base64

/**
 * El conector de la app para Claude Desktop o Claude Code (y cualquier programa que use MCP, el
 * sistema para añadirle herramientas a una IA): la app se arranca con `--mcp` y, sin abrir
 * ninguna ventana, atiende por la entrada y la salida estándar a la IA, que usa las [Acciones].
 *
 * Protocolo: JSON-RPC 2.0, un mensaje por línea (transporte "stdio" de MCP). Por la salida
 * estándar solo puede ir el protocolo: todo lo demás (avisos, errores) va a la salida de errores.
 */
class ServidorMcp(
    private val acciones: Acciones,
    private val entrada: InputStream,
    salida: OutputStream,
    private val version: String = VERSION_APP,
) {
    private val json = ObjectMapper()
    private val salida = PrintStream(salida, true, Charsets.UTF_8)

    /** Atiende mensajes hasta que se cierre la entrada (cuando la IA cierra el conector). */
    fun atender() {
        val lector = BufferedReader(InputStreamReader(entrada, Charsets.UTF_8))
        while (true) {
            val linea = lector.readLine() ?: return
            if (linea.isBlank()) continue
            val mensaje = try {
                json.readTree(linea)
            } catch (_: Exception) {
                responderError(null, -32700, "El mensaje no es JSON válido.")
                continue
            }
            if (mensaje.isArray) mensaje.forEach(::procesar) else procesar(mensaje)
        }
    }

    private fun procesar(mensaje: JsonNode) {
        val metodo = mensaje.path("method").asText(null)
        val id = mensaje.get("id")
        if (metodo == null) return // una respuesta del otro lado: aquí no se pide nada, así que se ignora
        if (id == null || id.isNull) return // notificación (p. ej. notifications/initialized): no se contesta
        val params = mensaje.path("params")
        when (metodo) {
            "initialize" -> responder(id, inicializar(params))
            "ping" -> responder(id, json.createObjectNode())
            "tools/list" -> responder(id, listarAcciones())
            "tools/call" -> responder(id, llamar(params))
            "resources/list" -> responder(id, json.createObjectNode().also { it.putArray("resources") })
            "prompts/list" -> responder(id, json.createObjectNode().also { it.putArray("prompts") })
            else -> responderError(id, -32601, "Método no admitido: $metodo")
        }
    }

    private fun inicializar(params: JsonNode): ObjectNode {
        val pedida = params.path("protocolVersion").asText("")
        return json.createObjectNode().apply {
            // Se contesta con la versión que pide la IA si se conoce; si no, con la más nueva que se conoce
            put("protocolVersion", if (pedida in VERSIONES) pedida else VERSIONES.last())
            putObject("capabilities").putObject("tools").put("listChanged", false)
            putObject("serverInfo").put("name", "jujamaker").put("version", version)
            put("instructions", acciones.instrucciones)
        }
    }

    private fun listarAcciones(): ObjectNode = json.createObjectNode().apply {
        val lista = putArray("tools")
        for (a in acciones.lista) {
            lista.addObject().apply {
                put("name", a.nombre)
                put("description", a.descripcion)
                set<ObjectNode>("inputSchema", a.parametros)
            }
        }
    }

    private fun llamar(params: JsonNode): ObjectNode {
        val r = acciones.ejecutar(params.path("name").asText(""), params.get("arguments"))
        return json.createObjectNode().apply {
            val contenido = putArray("content")
            for (parte in r.partes) {
                when (parte) {
                    is Acciones.Parte.Texto -> contenido.addObject().put("type", "text").put("text", parte.texto)
                    is Acciones.Parte.Imagen -> contenido.addObject()
                        .put("type", "image")
                        .put("data", Base64.getEncoder().encodeToString(parte.datos))
                        .put("mimeType", parte.tipo)
                }
            }
            put("isError", r.error)
        }
    }

    private fun responder(id: JsonNode, resultado: JsonNode) {
        escribir(json.createObjectNode().put("jsonrpc", "2.0").also { it.set<JsonNode>("id", id); it.set<JsonNode>("result", resultado) })
    }

    private fun responderError(id: JsonNode?, codigo: Int, mensaje: String) {
        val r = json.createObjectNode().put("jsonrpc", "2.0")
        if (id == null) r.putNull("id") else r.set<JsonNode>("id", id)
        r.putObject("error").put("code", codigo).put("message", mensaje)
        escribir(r)
    }

    @Synchronized
    private fun escribir(mensaje: JsonNode) {
        salida.print(json.writeValueAsString(mensaje)) // sin saltos de línea dentro: un mensaje por línea
        salida.print('\n')
        salida.flush()
    }

    companion object {
        /** Versiones del protocolo MCP que se conocen, de la más antigua a la más nueva. */
        val VERSIONES = listOf("2024-11-05", "2025-03-26", "2025-06-18")

        /** La versión de la app (la del instalador, si se lanza desde él). */
        val VERSION_APP: String = ServidorMcp::class.java.`package`?.implementationVersion ?: "1.0.0"

        /**
         * Arranca el conector (la app lanzada con --mcp): sin ventanas, con los datos de siempre
         * (ver [CarpetaDatos]). Lo que se imprima por error va a la salida de errores, para no
         * romper el protocolo.
         */
        fun arrancar() {
            System.setProperty("java.awt.headless", "true") // las imágenes se tratan sin abrir nada en pantalla
            val salidaReal = FileOutputStream(FileDescriptor.out)
            System.setOut(System.err)
            val datos = CarpetaDatos.elegida()
            try {
                Files.createDirectories(datos)
            } catch (_: Exception) {
                // si no se puede crear, cada acción dará su error
            }
            ServidorMcp(Acciones(Rutas(datos)), System.`in`, salidaReal).atender()
        }
    }
}
