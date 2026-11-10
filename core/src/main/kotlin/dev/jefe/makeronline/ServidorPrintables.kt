package dev.jefe.makeronline

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.system.exitProcess

/**
 * El servidor por el que la extensión de Chrome de Printables pide a la app los modelos de la cola
 * (ver ColaPrintables y la carpeta extension-printables/). Solo atiende en este ordenador
 * (127.0.0.1:[PUERTO]) y solo a extensiones de Chrome: las peticiones que cambian algo (POST) tienen
 * que venir de una extensión (cabecera Origin "chrome-extension://…"); una página web no puede poner
 * esa cabecera, así que ninguna web puede apuntar modelos como subidos.
 *
 *   GET  /printables/estado                  → {pendientes, conProblemas, subidas}
 *   GET  /printables/siguiente?saltar=a,b    → la ficha del siguiente modelo (o {"ficha": null})
 *   GET  /printables/archivo?carpeta=&nombre= → un archivo de esa ficha
 *   POST /printables/subido {carpeta, modelo} → apunta subido-printables.txt en la carpeta
 *
 * La app lo arranca al abrirse. También es un programa de terminal (configuración
 * "Cola de Printables"): lo arranca y espera hasta "parar" + ENTER.
 */
class ServidorPrintables(
    private val cola: ColaPrintables,
    /** Se avisa cada vez que se apunta un modelo como subido (clave e id del modelo en Printables). */
    private val alSubir: (String, String) -> Unit = { _, _ -> },
) : AutoCloseable {

    private val json = ObjectMapper()
    private var servidor: HttpServer? = null

    /**
     * Empieza a atender.
     *
     * @throws AgenteException si el puerto está ocupado (otra JujaMaker abierta, por ejemplo)
     */
    fun arrancar() {
        val s = try {
            HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), PUERTO), 0)
        } catch (e: BindException) {
            throw AgenteException("El puerto $PUERTO ya está en uso: ¿hay otra JujaMaker abierta? " +
                "La extensión de Printables solo puede hablar con una.")
        }
        s.createContext("/printables/") { ex ->
            try {
                atender(ex)
            } catch (e: Exception) {
                responder(ex, 500, json.createObjectNode().put("error", e.message ?: e.toString()))
            } finally {
                ex.close()
            }
        }
        // Un solo hilo: las peticiones de la extensión van de una en una y así no se pisan
        s.executor = Executors.newSingleThreadExecutor { r -> Thread(r, "servidor-printables").apply { isDaemon = true } }
        s.start()
        servidor = s
    }

    override fun close() {
        servidor?.stop(0)
        servidor = null
    }

    private fun atender(ex: HttpExchange) {
        val origen = ex.requestHeaders.getFirst("Origin")
        val deExtension = origen != null && origen.startsWith("chrome-extension://")
        // Sin Origin solo puede ser un GET de un programa de este ordenador o de la propia extensión
        if (!deExtension && (origen != null || ex.requestMethod != "GET")) {
            responder(ex, 403, json.createObjectNode().put("error", "solo para la extensión de JujaMaker"))
            return
        }
        val ruta = ex.requestURI.path.removePrefix("/printables/")
        val parametros = parametros(ex.requestURI.rawQuery)
        when {
            ex.requestMethod == "GET" && ruta == "estado" -> {
                val e = cola.estado()
                val r = json.createObjectNode()
                r.put("version", VERSION_PROTOCOLO)
                r.putArray("pendientes").also { a -> e.pendientes.forEach { a.add(it) } }
                r.putArray("conProblemas").also { a -> e.conProblemas.forEach { a.add(it) } }
                r.put("subidas", e.subidas)
                responder(ex, 200, r)
            }
            ex.requestMethod == "GET" && ruta == "siguiente" -> {
                val saltar = parametros["saltar"].orEmpty().split(',').filter { it.isNotBlank() }.toSet()
                val f = cola.siguiente(saltar)
                val r = json.createObjectNode()
                if (f == null) r.putNull("ficha") else r.set<ObjectNode>("ficha", ficha(f))
                responder(ex, 200, r)
            }
            ex.requestMethod == "GET" && ruta == "archivo" -> {
                val archivo = cola.archivo(parametros["carpeta"].orEmpty(), parametros["nombre"].orEmpty())
                if (archivo == null) {
                    responder(ex, 404, json.createObjectNode().put("error", "no existe ese archivo en la cola"))
                    return
                }
                ex.responseHeaders.add("Content-Type", tipo(archivo.fileName.toString()))
                ex.sendResponseHeaders(200, Files.size(archivo))
                ex.responseBody.use { Files.copy(archivo, it) }
            }
            ex.requestMethod == "POST" && ruta == "subido" -> {
                val cuerpo = json.readTree(ex.requestBody.readAllBytes())
                val clave = cuerpo.path("carpeta").asText("")
                val modelo = cuerpo.path("modelo").asText("")
                cola.anotarSubido(clave, modelo)
                alSubir(clave, modelo)
                responder(ex, 200, json.createObjectNode().put("ok", true))
            }
            else -> responder(ex, 404, json.createObjectNode().put("error", "no existe: ${ex.requestMethod} /printables/$ruta"))
        }
    }

    private fun ficha(f: ColaPrintables.Ficha): ObjectNode = json.createObjectNode().apply {
        put("clave", f.clave)
        put("titulo", f.titulo)
        put("resumen", f.resumen)
        put("descripcion", f.descripcion)
        putArray("etiquetas").also { a -> f.etiquetas.forEach { a.add(it) } }
        putObject("categoria").apply {
            put("grupo", f.categoria.grupo)
            put("nombre", f.categoria.nombre)
        }
        put("licencia", f.licencia)
        putArray("archivos").also { a ->
            for (ar in f.archivos) a.addObject().put("nombre", ar.nombre).put("tipo", ar.tipo.name.lowercase(Locale.ROOT))
        }
        putArray("avisos").also { a -> f.avisos.forEach { a.add(it) } }
    }

    private fun responder(ex: HttpExchange, codigo: Int, cuerpo: ObjectNode) {
        val bytes = json.writeValueAsBytes(cuerpo)
        ex.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        ex.sendResponseHeaders(codigo, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    companion object {
        /**
         * El puerto en el que escucha (la extensión lo tiene puesto en su manifest y en background.js).
         * Otro que el de MakerWorld (47811, rama makerworld), por si un día están las dos.
         */
        const val PUERTO = 47812
        /** Sube si cambia lo que se envía, para que la extensión avise si no son de la misma versión. */
        const val VERSION_PROTOCOLO = 1

        private fun parametros(consulta: String?): Map<String, String> =
            consulta.orEmpty().split('&').filter { it.isNotEmpty() }.associate { p ->
                val clave = URLDecoder.decode(p.substringBefore('='), Charsets.UTF_8)
                val valor = URLDecoder.decode(p.substringAfter('=', ""), Charsets.UTF_8)
                clave to valor
            }

        private fun tipo(nombre: String): String = when (nombre.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "stl" -> "model/stl"
            "3mf" -> "model/3mf"
            else -> "application/octet-stream"
        }

        /** Programa de terminal: arranca el servidor con la carpeta actual y espera hasta "parar". */
        @JvmStatic
        fun main(args: Array<String>) {
            val rutas = Rutas.carpetaActual()
            val progreso = ProgresoConsola()
            val cancelacion = Cancelacion()
            val cola = ColaPrintables(rutas)
            try {
                ServidorPrintables(cola) { clave, id -> progreso.correcto("$clave → modelo $id") }.use { s ->
                    s.arrancar()
                    val e = cola.estado()
                    progreso.info("Cola de Printables en http://127.0.0.1:$PUERTO · ${e.pendientes.size} por subir" +
                        (if (e.conProblemas.isNotEmpty()) ", ${e.conProblemas.size} con problemas" else ""))
                    progreso.info("Abre Printables en tu Chrome y usa la extensión de JujaMaker.")
                    EntradaConsola.iniciar(cancelacion, progreso)
                    while (!cancelacion.pedida()) Thread.sleep(500)
                }
            } catch (e: AgenteException) {
                progreso.error(e.message ?: e.toString())
                exitProcess(e.codigoSalida)
            }
            exitProcess(0)
        }
    }
}
