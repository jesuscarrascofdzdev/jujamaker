package dev.jefe.makeronline

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Descarga de Printables los modelos de los usuarios de usuarios-printables.json, con la misma
 * lógica que el Downloader de MakerOnline y DescargadorCreality: de cada modelo, sus archivos (.stl /
 * .3mf), la portada y un info.json con el título, la descripción y las etiquetas, en
 * <descargas>/<usuario> (Printables)/<título>_<id>/; al terminar cada uno, like; los que ya tienen tu
 * like se saltan; lo bajado se apunta en descargados-printables.txt (si está ahí pero sin like, solo se
 * le da el like); solo los gratis; los últimos N de cada usuario; un máximo por vez; pausa al azar.
 *
 * **Quien descarga es la extensión de Chrome** (extension-printables/descargas.js), no la app: Cloudflare
 * no deja entrar al Chrome que maneja la app (comprobado el 10/11/2026 con DiagnosticoPrintables). La
 * persona abre Printables en su Chrome de siempre y la extensión, con su sesión, pide la lista de modelos
 * y los enlaces de descarga a la propia web, baja los archivos y se los pasa a la app por
 * ServidorPrintables. Esta clase es la parte de la app: el trabajo que tiene que hacer la extensión, dónde
 * se guarda cada archivo, el info.json, el registro y la cuenta de cómo va.
 *
 * Solo hay un trabajo a la vez. Todo lo que llega de la extensión lleva el id del trabajo: si no es el
 * que está en marcha (otra pestaña, uno viejo), se rechaza. La extensión solo puede escribir dentro de
 * las carpetas de modelo que le ha dado la app ([carpeta]).
 */
class DescargasPrintables(private val rutas: Rutas) {

    /**
     * @property ultimos cuántos de los últimos modelos de cada usuario se miran (0 = todos)
     * @property max     máximo de modelos que se descargan de esta vez
     * @property simular solo cuenta lo que descargaría (no baja nada ni da like)
     * @property espera  pausa mínima entre modelos, en segundos (se le suma hasta [MARGEN_ESPERA] al azar)
     * @property saltar  entradas de la lista que no se repasan esta vez (las desmarcadas)
     */
    data class Opciones(
        val ultimos: Int,
        val max: Int,
        val simular: Boolean,
        val espera: Int = DescargadorCreality.ESPERA_POR_DEFECTO,
        val saltar: List<String> = emptyList(),
    )

    /** Cómo ha ido, con los mismos conceptos que DescargadorCreality.Resumen. */
    data class Resumen(
        val descargados: Int,
        val soloLike: Int,
        val yaTenianLike: Int,
        val saltados: Int,
        val errores: Int,
        val simulado: Boolean,
        val parado: Boolean,
    )

    private class Trabajo(
        val id: String,
        val op: Opciones,
        val usuarios: List<String>,
        val registrados: Set<String>,
        val progreso: Progreso,
        val alTerminar: (Resumen) -> Unit,
    ) {
        @Volatile var parar = false
        @Volatile var conectado = false
        /** Las carpetas de modelo que se le han dado a la extensión, por su clave ("usuario/modelo"). */
        val carpetas = HashMap<String, Path>()
        var descargados = 0
        var soloLike = 0
        var yaTenianLike = 0
        var saltados = 0
        var errores = 0

        fun resumen(parado: Boolean) =
            Resumen(descargados, soloLike, yaTenianLike, saltados, errores, op.simular, parado)
    }

    private val json = ObjectMapper()

    @Volatile
    private var trabajo: Trabajo? = null

    /** Si hay una descarga en marcha (esperando a la extensión o descargando). */
    val enMarcha: Boolean get() = trabajo != null

    /** Si la extensión ya ha cogido el trabajo en marcha. */
    val conectada: Boolean get() = trabajo?.conectado == true

    /**
     * Prepara un trabajo para la extensión. Empieza de verdad cuando la extensión lo pide (la persona
     * abre Printables con la marca de descargar, ver [DIRECCION]).
     *
     * @param progreso   donde se cuenta cómo va (llega desde el hilo del servidor)
     * @param alTerminar se llama una vez, cuando la extensión termina o se cancela
     * @throws AgenteException si la lista no vale, no hay nadie marcado o ya hay una descarga en marcha
     */
    @Synchronized
    fun empezar(op: Opciones, progreso: Progreso, alTerminar: (Resumen) -> Unit): String {
        if (trabajo != null) throw AgenteException("Ya hay una descarga de Printables en marcha.")
        val lectura = ListaUsuariosPrintables.leer(rutas.usuariosPrintables)
        if (lectura.error != null) throw AgenteException(lectura.error)
        val usuarios = lectura.entradas.filter { it !in op.saltar && ListaUsuariosPrintables.handleDe(it) != null }
        if (usuarios.isEmpty()) throw AgenteException("No hay ningún usuario de Printables marcado para descargar.")
        val id = UUID.randomUUID().toString()
        trabajo = Trabajo(id, op, usuarios, leerRegistro(), progreso, alTerminar)
        return id
    }

    /** "Parar": la extensión para en el siguiente punto seguro (entre archivos) y avisa con "fin". */
    fun parar() {
        trabajo?.parar = true
    }

    /**
     * Da el trabajo por terminado sin esperar a la extensión (no ha llegado a conectar, o se cierra la
     * app). Lo que esté completo (con info.json) va igualmente al zip.
     */
    fun cancelar() {
        terminar(parado = true)
    }

    // ------------------------------------------------------------ lo que pide la extensión (ServidorPrintables)

    /** GET descargas/trabajo: el trabajo en marcha, o {"activo": false}. */
    fun trabajoJson(): ObjectNode {
        val t = trabajo ?: return json.createObjectNode().put("activo", false)
        t.conectado = true
        return json.createObjectNode().apply {
            put("activo", true)
            put("trabajo", t.id)
            put("parar", t.parar)
            put("ultimos", t.op.ultimos)
            put("max", t.op.max)
            put("simular", t.op.simular)
            put("espera", t.op.espera)
            put("margen", DescargadorCreality.MARGEN_ESPERA)
            putArray("usuarios").also { a ->
                for (e in t.usuarios) a.addObject().put("entrada", e).put("handle", ListaUsuariosPrintables.handleDe(e))
            }
            putArray("registrados").also { a -> t.registrados.forEach { a.add(it) } }
        }
    }

    /**
     * POST descargas/evento: cómo va ({tipo, texto, …}). Devuelve si hay que parar. Los tipos que
     * cuentan para el resumen: "saltado", "ya-like", "solo-like", "error" y, al simular, "descargaria".
     */
    fun evento(cuerpo: JsonNode): ObjectNode {
        val t = delTrabajo(cuerpo)
        val p = t.progreso
        val texto = cuerpo.path("texto").asText("")
        synchronized(t) {
            when (cuerpo.path("tipo").asText("")) {
                "usuario" -> {
                    val entrada = cuerpo.path("entrada").asText("")
                    val nombre = cuerpo.path("nombre").asText("")
                    // La primera vez, se le pone su nombre de verdad en la lista
                    val nueva = if (entrada.isNotEmpty() && nombre.isNotBlank()) {
                        ListaUsuariosPrintables.ponerNombre(rutas.usuariosPrintables, entrada, nombre)
                    } else {
                        entrada
                    }
                    p.usuario(cuerpo.path("indice").asInt(1), cuerpo.path("total").asInt(1), nueva)
                }
                "modelo" -> p.modelo(cuerpo.path("indice").asInt(1), cuerpo.path("total").asInt(1), texto)
                "paginas" -> p.leyendoPaginas(cuerpo.path("pagina").asInt(1), cuerpo.path("ultima").asInt(1))
                "pausa" -> p.pausa(cuerpo.path("segundos").asLong(0))
                "paso" -> p.paso(texto)
                "correcto" -> p.correcto(texto)
                "like" -> p.like(texto)
                "nota" -> p.nota(texto)
                "saltado" -> {
                    t.saltados++
                    p.saltado(texto)
                }
                "ya-like" -> {
                    t.yaTenianLike++
                    p.saltado(texto)
                }
                "solo-like" -> {
                    t.soloLike++
                    p.like(texto)
                }
                "descargaria" -> {
                    t.descargados++
                    p.info(texto)
                }
                "error" -> {
                    t.errores++
                    p.error(texto)
                    anotarError(cuerpo)
                }
                else -> p.info(texto)
            }
        }
        return json.createObjectNode().put("parar", t.parar)
    }

    /**
     * POST descargas/carpeta {usuario, id, titulo}: crea la carpeta de un modelo en
     * <descargas>/<usuario> (Printables)/<título>_<id>/ y devuelve su clave, que es lo único con lo que
     * la extensión puede escribir archivos.
     */
    fun carpeta(cuerpo: JsonNode): ObjectNode {
        val t = delTrabajo(cuerpo)
        val id = cuerpo.path("id").asText("").filter { it.isDigit() }
        require(id.isNotEmpty()) { "falta el id del modelo" }
        val usuario = DescargadorCreality.limpio(cuerpo.path("usuario").asText("").ifBlank { "usuario" } + " (Printables)", 70)
        val modelo = DescargadorCreality.limpio(cuerpo.path("titulo").asText("").ifBlank { id }, 60) + "_" + id
        val dir = rutas.paraLaApp().descargas.resolve(usuario).resolve(modelo)
        Files.createDirectories(dir)
        val clave = "$usuario/$modelo"
        synchronized(t) { t.carpetas[clave] = dir }
        return json.createObjectNode().put("carpeta", clave)
    }

    /**
     * POST descargas/archivo?carpeta=&nombre= (el cuerpo es el archivo): lo guarda en esa carpeta de
     * modelo. Si ya hay uno con ese nombre, le pone "_2", "_3"… Un .3mf tiene que llegar entero (es un
     * zip). Devuelve el nombre con el que se ha guardado.
     */
    fun archivo(trabajoId: String, clave: String, nombre: String, datos: ByteArray): ObjectNode {
        val t = trabajo?.takeIf { it.id == trabajoId } ?: throw TrabajoNoValido()
        val dir = synchronized(t) { t.carpetas[clave] } ?: throw IllegalArgumentException("carpeta no válida: $clave")
        if (datos.isEmpty()) throw IllegalArgumentException("$nombre ha llegado vacío")
        val limpio = DescargadorCreality.limpio(nombre, 120)
        val base = limpio.substringBeforeLast('.', limpio)
        val ext = limpio.substringAfterLast('.', "").let { if (it.isEmpty() || it == limpio) "" else ".$it" }
        var destino = dir.resolve(base + ext)
        var n = 2
        while (Files.exists(destino)) destino = dir.resolve("${base}_${n++}$ext")
        if (ext.equals(".3mf", ignoreCase = true) && !zipCompleto(datos)) {
            throw IllegalArgumentException("$nombre ha llegado cortado o no es un .3mf")
        }
        val parcial = destino.resolveSibling(destino.fileName.toString() + ".part")
        Files.write(parcial, datos)
        Files.move(parcial, destino, StandardCopyOption.REPLACE_EXISTING)
        return json.createObjectNode().put("nombre", destino.fileName.toString()).put("bytes", datos.size)
    }

    /**
     * POST descargas/info {carpeta, id, titulo, autor, usuario, url, categoria, licencia, etiquetas,
     * descripcion}: escribe el info.json (lo último: si está, el modelo está completo) y lo apunta en
     * descargados-printables.txt.
     */
    fun info(cuerpo: JsonNode): ObjectNode {
        val t = delTrabajo(cuerpo)
        val clave = cuerpo.path("carpeta").asText("")
        val dir = synchronized(t) { t.carpetas[clave] } ?: throw IllegalArgumentException("carpeta no válida: $clave")
        val id = cuerpo.path("id").asText("").filter { it.isDigit() }
        val titulo = cuerpo.path("titulo").asText("").ifBlank { id }
        val info = json.createObjectNode()
        info.put("id", id)
        info.put("title", titulo)
        info.put("author", cuerpo.path("autor").asText(""))
        info.put("url", "${ColaPrintables.WEB}/model/$id")
        info.put("source", "Printables")
        info.put("category", cuerpo.path("categoria").asText(""))
        info.put("license", cuerpo.path("licencia").asText(""))
        val etiquetas = info.putArray("tags")
        for (e in cuerpo.path("etiquetas")) e.asText("").trim().takeIf { it.isNotEmpty() }?.let { etiquetas.add(it) }
        info.put("description", DescargadorCreality.htmlATexto(cuerpo.path("descripcion").asText("")))
        info.put("downloadedAt", LocalDateTime.now().format(FECHA))
        json.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("info.json").toFile(), info)
        registrar(id, cuerpo.path("usuario").asText(""), titulo)
        synchronized(t) { t.descargados++ }
        t.progreso.correcto("Descargado: $titulo")
        return json.createObjectNode().put("ok", true).put("parar", t.parar)
    }

    /** POST descargas/fin {parado}: la extensión ha terminado (o ha parado). */
    fun fin(cuerpo: JsonNode): ObjectNode {
        delTrabajo(cuerpo)
        terminar(parado = cuerpo.path("parado").asBoolean(false))
        return json.createObjectNode().put("ok", true)
    }

    /** El trabajo en marcha si [cuerpo] es suyo; si no, la extensión tiene que parar. */
    private fun delTrabajo(cuerpo: JsonNode): Trabajo {
        val t = trabajo
        if (t == null || cuerpo.path("trabajo").asText("") != t.id) throw TrabajoNoValido()
        t.conectado = true
        return t
    }

    /** Lo que llega de un trabajo que ya no está en marcha: el servidor responde 409 y la extensión para. */
    class TrabajoNoValido : RuntimeException("no hay ninguna descarga de Printables en marcha con ese id")

    private fun terminar(parado: Boolean) {
        val t = synchronized(this) {
            val actual = trabajo ?: return
            trabajo = null
            actual
        }
        t.parar = true
        val resumen = synchronized(t) { t.resumen(parado) }
        try {
            t.alTerminar(resumen)
        } catch (_: Exception) {
            // lo que haga la app al terminar no puede tumbar al servidor
        }
    }

    // ------------------------------------------------------------ registro y errores

    private fun leerRegistro(): Set<String> {
        val registro = rutas.registroPrintables
        if (!Files.exists(registro)) return emptySet()
        return Files.readAllLines(registro, StandardCharsets.UTF_8)
            .map { it.split("\t", limit = 2)[0].trim() }.filter { it.isNotEmpty() }.toSet()
    }

    /** Apunta el modelo en descargados-printables.txt: id ⇥ usuario ⇥ título ⇥ fecha. */
    private fun registrar(id: String, usuario: String, titulo: String) {
        try {
            val linea = listOf(id, usuario, titulo.replace('\t', ' '), LocalDateTime.now().format(FECHA)).joinToString("\t") + "\n"
            Files.writeString(
                rutas.registroPrintables, linea, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        } catch (e: IOException) {
            trabajo?.progreso?.error("No he podido apuntarlo en ${rutas.registroPrintables.fileName}: ${e.message}")
        }
    }

    /** Si el error es de un modelo, se apunta en errores.txt de la carpeta del usuario (como en Creality). */
    private fun anotarError(cuerpo: JsonNode) {
        val usuario = cuerpo.path("usuario").asText("")
        if (usuario.isBlank()) return
        try {
            val dir = rutas.paraLaApp().descargas.resolve(DescargadorCreality.limpio("$usuario (Printables)", 70))
            Files.createDirectories(dir)
            val modelo = cuerpo.path("modelo").asText("")
            val texto = "${LocalDateTime.now().format(FECHA)}  $modelo\n  ${cuerpo.path("texto").asText("")}\n\n"
            Files.writeString(dir.resolve("errores.txt"), texto, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
        } catch (_: IOException) {
        }
    }

    companion object {
        /**
         * Lo que abre la app en el Chrome de siempre para empezar: la portada de Printables con la marca
         * de descargar (va tras "#", así Printables ni la ve). La extensión la ve y pide el trabajo.
         */
        const val DIRECCION = "${ColaPrintables.WEB}/#jujamaker-descargar"

        private val FECHA: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        /** true si los datos empiezan y acaban como un zip (así son los .3mf). */
        @JvmStatic
        fun zipCompleto(datos: ByteArray): Boolean {
            if (datos.size < 22 || datos[0] != 'P'.code.toByte() || datos[1] != 'K'.code.toByte()) return false
            // La marca del final de un zip (bytes 50 4B 05 06) está en los últimos 64 KB
            val desde = maxOf(0, datos.size - 65_557)
            for (i in datos.size - 22 downTo desde) {
                if (datos[i] == 0x50.toByte() && datos[i + 1] == 0x4B.toByte() && datos[i + 2] == 0x05.toByte() && datos[i + 3] == 0x06.toByte()) {
                    return true
                }
            }
            return false
        }
    }
}
