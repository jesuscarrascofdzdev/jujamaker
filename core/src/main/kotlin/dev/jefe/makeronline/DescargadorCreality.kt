package dev.jefe.makeronline

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.options.RequestOptions
import java.io.IOException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference

/**
 * Descarga de Creality Cloud los modelos de los usuarios de usuarios-creality.json: de cada modelo,
 * sus perfiles de impresión (.3mf), la portada y un info.json con el título, la descripción y las
 * etiquetas. Deja cada uno en <descargas>/<usuario> (Creality)/<título>_<id>/, igual que el
 * Downloader de MakerOnline, para que sirva lo mismo después (zip, subir…).
 *
 * Cómo habla con la web (comprobado el 10/11/2026, ver claude/04-contexto-creality-cloud.md):
 *  - abre Creality Cloud en el Chrome de siempre (con la sesión) y copia de una petición de la
 *    propia página las cabeceras `__cxy_…` (ahí va la sesión); con ellas hace las mismas peticiones
 *    que hace la página. Esas cabeceras no se guardan ni se enseñan en ningún sitio;
 *  - modelos de un usuario: `listSharePage` (de 12 en 12, los más nuevos primero);
 *  - detalle de un modelo: `modelGroupDetail` → `groupItem.model3mfList`;
 *  - cada .3mf: `3mfDownload` devuelve una dirección firmada de descarga (caduca); al acabar,
 *    `3mfDownloadSuccess` (es lo que hace el botón "Descargar 3MF").
 *
 * Al terminar cada modelo le da like (`modelGroupLike`, comprobado el 10/11/2026), como en MakerOnline:
 * los que ya tienen tu like (`isLike` en la lista) se saltan sin abrirlos. Además, lo descargado se
 * apunta en descargados-creality.txt: si un modelo está ahí pero sin like, solo se le da el like.
 * De momento no baja los modelos de pago ni los que no tienen perfil .3mf (solo STL).
 *
 * Ritmo prudente: una descarga cada vez y una pausa al azar entre modelos, contada desde el inicio
 * del anterior. No coge el candado de Chrome (AgentLock): lo coge quien lo llama.
 */
class DescargadorCreality(
    private val rutas: Rutas,
    private val progreso: Progreso,
    private val cancelacion: Cancelacion = Cancelacion(),
) {

    /**
     * @property ultimos   cuántos de los últimos modelos de cada usuario se miran (0 = todos)
     * @property simular   solo cuenta lo que descargaría
     * @property max       máximo de modelos que se descargan de esta vez
     * @property espera    pausa mínima entre modelos, en segundos (se le suma hasta [MARGEN_ESPERA] al azar)
     * @property modo      cómo se ve Chrome: fuera de la pantalla, a la vista o sin ventana (como en MakerOnline)
     * @property saltar    entradas de la lista que no se repasan esta vez (las desmarcadas)
     * @property inicioAnterior cuándo empezó la última descarga de otra web en esta misma vez
     *           (milisegundos del reloj; 0 = ninguna): la primera de aquí también respeta la pausa
     */
    data class Opciones(
        val ultimos: Int,
        val simular: Boolean,
        val max: Int,
        val espera: Int,
        val modo: Downloader.Modo,
        val saltar: List<String> = emptyList(),
        val inicioAnterior: Long = 0L,
    )

    /**
     * @property descargados  modelos descargados (o que se descargarían, si es una simulación)
     * @property soloLike     ya estaban en descargados-creality.txt y solo les faltaba el like
     * @property yaTenianLike ya tenían tu like: no se miran
     * @property saltados     modelos que no se bajan (de pago o sin perfil .3mf)
     * @property errores      modelos o usuarios que han fallado
     */
    data class Resumen(
        val descargados: Int,
        val soloLike: Int,
        val yaTenianLike: Int,
        val saltados: Int,
        val errores: Int,
        val simulado: Boolean,
        val parado: Boolean,
    )

    private var cabeceras: Map<String, String> = emptyMap()
    private val registrados = HashSet<String>()
    private var inicioUltimo = 0L
    private var pausar = false

    fun ejecutar(op: Opciones): Resumen {
        val lectura = ListaUsuariosCreality.leer(rutas.usuariosCreality)
        if (lectura.error != null) throw AgenteException(lectura.error)
        val entradas = lectura.entradas.filter { it !in op.saltar }
        if (entradas.isEmpty()) throw AgenteException("No hay ningún usuario de Creality Cloud marcado para descargar.")
        leerRegistro()

        var descargados = 0
        var soloLike = 0
        var yaTenianLike = 0
        var saltados = 0
        var errores = 0
        var parado = false
        var restantes = op.max
        if (op.inicioAnterior > 0) {
            inicioUltimo = op.inicioAnterior
            pausar = true
        }

        Browsers.playwright().use { pw ->
            Browsers.openChrome(
                pw, rutas.perfilChrome, 0.0,
                op.modo == Downloader.Modo.SIN_VENTANA, op.modo == Downloader.Modo.APARTADA,
            ).use { ctx ->
                conectar(ctx)
                try {
                    usuarios@ for ((i, entradaLista) in entradas.withIndex()) {
                        cancelacion.comprobar()
                        var entrada = entradaLista
                        progreso.usuario(i + 1, entradas.size, entrada)
                        val idUsuario = ListaUsuariosCreality.idDe(entrada)
                        if (idUsuario == null) {
                            errores++
                            progreso.error("No encuentro el número de usuario en \"$entrada\".")
                            continue
                        }
                        val modelos = try {
                            modelosDe(ctx, idUsuario, op.ultimos)
                        } catch (e: CanceladoException) {
                            throw e
                        } catch (e: Exception) {
                            errores++
                            progreso.error("No he podido leer los modelos de ${nombreDe(entrada)}: ${e.message}")
                            continue
                        }
                        // La primera vez, se le pone su nombre de verdad en la lista
                        val nick = modelos.firstOrNull()?.path("userInfo")?.path("nickName")?.asText("").orEmpty()
                        entrada = ListaUsuariosCreality.ponerNombre(rutas.usuariosCreality, entrada, nick)
                        val nombre = ListaUsuariosCreality.nombreDe(entrada) ?: nick.ifBlank { "usuario $idUsuario" }
                        val carpetaUsuario = rutas.descargas.resolve(limpio("$nombre (Creality)", 70))
                        progreso.info("$nombre: ${modelos.size} modelos para mirar")

                        for ((j, m) in modelos.withIndex()) {
                            cancelacion.comprobar()
                            val id = m.path("id").asText("")
                            val titulo = m.path("groupName").asText("").ifBlank { id }
                            progreso.modelo(j + 1, modelos.size, titulo)
                            when {
                                id.isEmpty() -> saltados++
                                m.path("isLike").asBoolean(false) -> {
                                    yaTenianLike++
                                    progreso.saltado("Ya tenía like, lo salto: $titulo")
                                }
                                id in registrados -> {
                                    // Bajado antes (sin like): solo se le da el like
                                    if (op.simular) {
                                        soloLike++
                                        progreso.nota("Ya estaba descargado; solo le daría like: $titulo")
                                    } else if (darLike(ctx, id, titulo)) {
                                        soloLike++
                                        esperar(PAUSA_LIKE + ThreadLocalRandom.current().nextLong(PAUSA_LIKE))
                                    } else {
                                        errores++
                                    }
                                }
                                m.path("isPay").asBoolean(false) -> {
                                    saltados++
                                    progreso.saltado("Es de pago, no se descarga: $titulo")
                                }
                                m.path("model3mfCount").asInt(0) < 1 -> {
                                    saltados++
                                    progreso.saltado("No tiene perfil .3mf, de momento no se descarga: $titulo")
                                }
                                restantes <= 0 -> {
                                    progreso.info("Se ha llegado al máximo de descargas de esta vez (${op.max}).")
                                    break@usuarios
                                }
                                op.simular -> {
                                    descargados++
                                    restantes--
                                    progreso.nota("Se descargaría: $titulo")
                                }
                                else -> {
                                    pausa(op.espera)
                                    inicioUltimo = System.currentTimeMillis()
                                    pausar = true
                                    try {
                                        val dir = descargar(ctx, id, titulo, nombre, carpetaUsuario)
                                        registrar(id, nombre, titulo)
                                        descargados++
                                        restantes--
                                        progreso.correcto("Descargado: $titulo → $dir")
                                        // Si el like falla, el modelo ya está bajado y apuntado: solo se avisa
                                        darLike(ctx, id, titulo)
                                    } catch (e: CanceladoException) {
                                        throw e
                                    } catch (e: Exception) {
                                        errores++
                                        progreso.error("Error al descargar $titulo: ${e.message}")
                                        anotarError(carpetaUsuario, id, titulo, e)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: CanceladoException) {
                    parado = true
                    progreso.info("Parado a petición tuya.")
                }
            }
        }
        return Resumen(descargados, soloLike, yaTenianLike, saltados, errores, op.simular, parado)
    }

    // ------------------------------------------------------------ sesión y peticiones

    /**
     * Abre Creality Cloud y copia las cabeceras `__cxy_…` de una petición de la propia página.
     * La de la sesión vale lo mismo que la cookie `model_token` (comprobado el 10/11/2026): si la
     * página no la manda pero la cookie está, se pone desde la cookie. Sin cookie, no hay sesión.
     */
    private fun conectar(ctx: BrowserContext) {
        val vistas = AtomicReference<Map<String, String>?>(null)
        val page = ctx.pages().firstOrNull() ?: ctx.newPage()
        page.onRequest { r ->
            if ("/api/cxy/" in r.url() && vistas.get() == null) {
                val h = r.headers().filterKeys { it.startsWith("__cxy_") }
                if (h.isNotEmpty()) vistas.set(h)
            }
        }
        progreso.paso("Abriendo Creality Cloud…")
        page.navigate(Web.CREALITY.inicio)
        val limite = System.currentTimeMillis() + 30_000L
        while (vistas.get() == null && System.currentTimeMillis() < limite) {
            cancelacion.comprobar()
            page.waitForTimeout(500.0)
        }
        val cookie = ctx.cookies(Web.CREALITY.inicio)
            .firstOrNull { it.name == COOKIE_SESION && !it.value.isNullOrBlank() }?.value
            ?: throw AgenteException("No hay sesión de Creality Cloud en Chrome (o ha caducado). Iníciala en la pantalla Sesión.")
        val h = HashMap(
            vistas.get() ?: throw AgenteException(
                "Creality Cloud no ha terminado de cargar (no he visto ninguna petición suya). Prueba otra vez en un rato."
            ),
        )
        if (h[CABECERA_SESION].isNullOrBlank()) h[CABECERA_SESION] = URLDecoder.decode(cookie, StandardCharsets.UTF_8)
        cabeceras = h
    }

    /** Una petición a la API de Creality, como las hace su página. Devuelve `result`. */
    private fun api(ctx: BrowserContext, ruta: String, cuerpo: Map<String, Any>): JsonNode {
        val opciones = RequestOptions.create().setData(cuerpo).setTimeout(60_000.0)
            .setHeader("Origin", BASE).setHeader("Referer", "$BASE/")
        for ((k, v) in cabeceras) {
            // El identificador de petición es uno nuevo cada vez, como hace la página (comprobado)
            val valor = if (k == CABECERA_PETICION && ES_UUID.matches(v)) UUID.randomUUID().toString() else v
            opciones.setHeader(k, valor)
        }
        val r = ctx.request().post(BASE + ruta, opciones)
        val j = try {
            if (!r.ok()) throw IllegalStateException("Creality ha respondido HTTP ${r.status()} en $ruta")
            JSON.readTree(r.text())
        } catch (e: IOException) {
            throw IllegalStateException("Creality ha respondido algo que no se entiende en $ruta")
        } finally {
            r.dispose()
        }
        val codigo = j.path("code").asInt(-1)
        if (codigo != 0) {
            throw IllegalStateException("Creality dice: ${j.path("msg").asText("código $codigo")} (código $codigo, $ruta)")
        }
        return j.path("result")
    }

    /** Los últimos [ultimos] modelos públicos de un usuario (0 = todos), los más nuevos primero. */
    private fun modelosDe(ctx: BrowserContext, idUsuario: Long, ultimos: Int): List<JsonNode> {
        val modelos = ArrayList<JsonNode>()
        var pagina = 1
        while (true) {
            cancelacion.comprobar()
            val r = api(
                ctx, "/api/cxy/v3/model/listSharePage",
                mapOf("page" to pagina, "pageSize" to PAGINA, "userId" to idUsuario, "displayVersions" to listOf("cxy-gen2")),
            )
            val lista = r.path("list")
            if (!lista.isArray || lista.isEmpty) break
            // add() y no +=: un JsonNode también es una lista de JsonNode y Kotlin no sabría cuál de los dos usar
            for (m in lista) modelos.add(m)
            if (ultimos > 0 && modelos.size >= ultimos) break
            if (lista.size() < PAGINA || pagina >= MAX_PAGINAS) break
            pagina++
            esperar(1500) // entre páginas de la lista, sin prisa
        }
        return if (ultimos > 0) modelos.take(ultimos) else modelos
    }

    /**
     * Da like a un modelo, como el corazón de su página: `modelGroupLike {id, action: true}`.
     * Devuelve false (y lo avisa) si no ha podido.
     */
    private fun darLike(ctx: BrowserContext, id: String, titulo: String): Boolean = try {
        api(ctx, "/api/cxy/v3/model/modelGroupLike", mapOf("id" to id, "action" to true))
        progreso.like("Like dado: $titulo")
        true
    } catch (e: Exception) {
        progreso.error("No he podido dar like a $titulo: ${e.message}")
        false
    }

    // ------------------------------------------------------------ descarga de un modelo

    /** Baja los .3mf, la portada y el info.json de un modelo. Devuelve su carpeta. */
    private fun descargar(ctx: BrowserContext, id: String, titulo: String, usuario: String, carpetaUsuario: Path): Path {
        val detalle = api(ctx, "/api/cxy/v3/model/modelGroupDetail", mapOf("id" to id)).path("groupItem")
        val perfiles = detalle.path("model3mfList")
        if (!perfiles.isArray || perfiles.isEmpty) throw IllegalStateException("el modelo no trae ningún perfil .3mf")

        val dir = carpetaUsuario.resolve(limpio(titulo, 60) + "_" + id)
        Files.createDirectories(dir)
        val usados = HashSet<String>()
        for (p in perfiles) {
            cancelacion.comprobar()
            val id3mf = p.path("id").asText("")
            if (id3mf.isEmpty()) continue
            val url = api(ctx, "/api/cxy/v3/model/3mfDownload", mapOf("id" to id3mf)).path("downloadUrl").asText("")
            if (url.isEmpty()) throw IllegalStateException("Creality no ha dado la dirección de descarga del .3mf")
            val base = p.path("name").asText("").replace(Regex("(?i)\\.3mf$"), "").ifBlank { titulo }
            var nombre = limpio(base, 80)
            var n = 2
            while (!usados.add(nombre.lowercase())) nombre = limpio(base, 76) + "_" + n++
            progreso.paso("Descargando $nombre.3mf…")
            val archivo = dir.resolve("$nombre.3mf")
            bajar(ctx, url, archivo)
            // Un .3mf es un zip: si le falta el final, ha llegado cortado. (El `size` que da Creality
            // no vale para comprobarlo: el archivo real suele ocupar 1 KB más; visto el 10/11/2026.)
            if (!zipCompleto(archivo)) {
                Files.deleteIfExists(archivo)
                throw IllegalStateException("$nombre.3mf ha llegado cortado o no es un .3mf")
            }
            // Lo que hace la página al terminar la descarga; si falla, el archivo ya está aquí
            try {
                api(ctx, "/api/cxy/v3/model/3mfDownloadSuccess", mapOf("id3mf" to id3mf))
            } catch (_: Exception) {
            }
        }

        // La portada (la de la web; si no, la primera que haya)
        val portada = listOf("pcCovers", "covers", "appCovers")
            .map { detalle.path(it).path(0).path("url").asText("") }
            .firstOrNull { it.isNotEmpty() }
        if (portada != null) {
            try {
                bajar(ctx, portada, dir.resolve("portada" + extension(portada)))
            } catch (e: Exception) {
                progreso.nota("No he podido bajar la portada: ${e.message}")
            }
        }

        // info.json, lo último: si está, la descarga se completó
        val info = JSON.createObjectNode()
        info.put("id", id)
        info.put("title", detalle.path("groupName").asText(titulo))
        info.put("author", detalle.path("userInfo").path("nickName").asText(usuario))
        info.put("url", "${Web.CREALITY.inicio}model-detail/$id")
        info.put("source", "Creality Cloud")
        // `categories` viene de la más concreta a la más general: aquí queda "Miniatures > Other"
        info.put("category", detalle.path("categories").mapNotNull { c -> c.path("enName").asText("").ifBlank { c.path("name").asText("") }.ifEmpty { null } }.reversed().joinToString(" > "))
        info.put("license", detalle.path("license").asText(""))
        // Cada etiqueta trae `alias` (como la escribió el autor) y `name` (traducida al idioma de la página)
        val etiquetas = info.putArray("tags")
        for (t in detalle.path("tags")) {
            val texto = if (t.isTextual) t.asText() else t.path("alias").asText("").ifBlank { t.path("name").asText("") }
            if (texto.isNotBlank()) etiquetas.add(texto)
        }
        // La descripción del autor (`groupDesc` puede venir traducida)
        val descripcion = detalle.path("originalGroupDesc").asText("").ifBlank { detalle.path("groupDesc").asText("") }
        info.put("description", htmlATexto(descripcion))
        info.put("downloadedAt", LocalDateTime.now().format(FECHA))
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("info.json").toFile(), info)
        return dir
    }

    /** Baja un archivo a [destino] (primero a un .part, para no dejar archivos a medias). Devuelve su tamaño. */
    private fun bajar(ctx: BrowserContext, url: String, destino: Path): Long {
        val r = ctx.request().get(url, RequestOptions.create().setTimeout(10 * 60_000.0))
        try {
            if (!r.ok()) throw IllegalStateException("la descarga ha respondido HTTP ${r.status()}")
            val datos = r.body()
            if (datos.isEmpty()) throw IllegalStateException("la descarga ha llegado vacía")
            val parcial = destino.resolveSibling(destino.fileName.toString() + ".part")
            Files.write(parcial, datos)
            Files.move(parcial, destino, StandardCopyOption.REPLACE_EXISTING)
            return datos.size.toLong()
        } finally {
            r.dispose()
        }
    }

    /** true si el archivo empieza y acaba como un zip (así son los .3mf). */
    private fun zipCompleto(archivo: Path): Boolean {
        val datos = Files.readAllBytes(archivo)
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

    // ------------------------------------------------------------ ritmo, registro y errores

    /** Pausa entre modelos: [espera] + hasta [MARGEN_ESPERA] s al azar, contada desde el inicio del anterior. */
    private fun pausa(espera: Int) {
        if (!pausar) return
        val total = (espera + ThreadLocalRandom.current().nextInt(MARGEN_ESPERA + 1)) * 1000L
        val falta = inicioUltimo + total - System.currentTimeMillis()
        if (falta <= 0) return
        progreso.pausa((falta + 999) / 1000)
        esperar(falta)
    }

    /** Espera sin dejar de mirar si se ha pedido parar. */
    private fun esperar(ms: Long) {
        val fin = System.currentTimeMillis() + ms
        while (true) {
            cancelacion.comprobar()
            val queda = fin - System.currentTimeMillis()
            if (queda <= 0) return
            Thread.sleep(minOf(queda, 250L))
        }
    }

    private fun leerRegistro() {
        registrados.clear()
        val registro = rutas.registroCreality
        if (!Files.exists(registro)) return
        for (linea in Files.readAllLines(registro, StandardCharsets.UTF_8)) {
            val id = linea.split("\t", limit = 2)[0].trim()
            if (id.isNotEmpty()) registrados += id
        }
    }

    /** Apunta el modelo en descargados-creality.txt: id ⇥ usuario ⇥ título ⇥ fecha. */
    private fun registrar(id: String, usuario: String, titulo: String) {
        registrados += id
        try {
            val linea = listOf(id, usuario, titulo.replace('\t', ' '), LocalDateTime.now().format(FECHA)).joinToString("\t") + "\n"
            Files.writeString(
                rutas.registroCreality, linea, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        } catch (e: IOException) {
            progreso.error("No he podido apuntarlo en ${rutas.registroCreality.fileName}: ${e.message}")
        }
    }

    private fun anotarError(carpetaUsuario: Path, id: String, titulo: String, e: Exception) {
        try {
            Files.createDirectories(carpetaUsuario)
            val texto = "${LocalDateTime.now().format(FECHA)}  $titulo ($id)\n  ${e.message ?: e.toString()}\n\n"
            Files.writeString(
                carpetaUsuario.resolve("errores.txt"), texto, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND,
            )
        } catch (_: IOException) {
        }
    }

    private fun nombreDe(entrada: String): String =
        ListaUsuariosCreality.nombreDe(entrada) ?: "usuario ${ListaUsuariosCreality.idDe(entrada) ?: entrada}"

    companion object {
        /** Se suma al azar a la pausa mínima: con 10 s de mínimo, entre 10 y 30 s. */
        const val MARGEN_ESPERA = 20
        const val ESPERA_POR_DEFECTO = 10
        /** Pausa tras dar like a un modelo ya descargado (milisegundos; se le suma otro tanto al azar). */
        private const val PAUSA_LIKE = 2000L

        private const val BASE = "https://www.crealitycloud.com"
        private const val CABECERA_SESION = "__cxy_token_"
        /** La cookie de la sesión (la misma que mira Sesion.kt). */
        private const val COOKIE_SESION = "model_token"
        private const val CABECERA_PETICION = "__cxy_requestid_"
        /** La página pide la lista de 12 en 12. */
        private const val PAGINA = 12
        private const val MAX_PAGINAS = 100
        private val ES_UUID = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        private val JSON = ObjectMapper()
        private val FECHA: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        private fun limpio(nombre: String, max: Int): String {
            var s = nombre.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").replace(Regex("\\s+"), " ").trim()
            if (s.length > max) s = s.substring(0, max).trim()
            s = s.replace(Regex("[. ]+$"), "")
            return s.ifEmpty { "modelo" }
        }

        private fun extension(url: String): String {
            val ruta = url.replace(Regex("[?#].*$"), "")
            val ext = ruta.substringAfterLast('.', "").lowercase()
            return if (ext.matches(Regex("jpe?g|png|webp|gif"))) ".$ext" else ".jpg"
        }

        private fun htmlATexto(html: String): String {
            var t = html.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</(p|li|h[1-6])>"), "\n").replace(Regex("<[^>]+>"), "")
            t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&ndash;", "–").replace("&mdash;", "—").replace("&amp;", "&")
            return t.lines().joinToString("\n") { it.trim() }.replace(Regex("\n{3,}"), "\n\n").trim()
        }

        /** Para lanzarlo desde la terminal: `--simular`, `--visible`, `--ultimos=N`, `--max=N`. */
        @JvmStatic
        fun main(args: Array<String>) {
            val rutas = Rutas.carpetaActual()
            val progreso = ProgresoConsola()
            val numero = { clave: String, porDefecto: Int ->
                args.firstOrNull { it.startsWith("--$clave=") }?.substringAfter('=')?.toIntOrNull() ?: porDefecto
            }
            try {
                AgentLock.acquire(rutas.candado, "Descargar de Creality", false, progreso).use {
                    val r = DescargadorCreality(rutas, progreso).ejecutar(
                        Opciones(numero("ultimos", 10), "--simular" in args, numero("max", Int.MAX_VALUE), ESPERA_POR_DEFECTO,
                            if ("--visible" in args) Downloader.Modo.VISIBLE else Downloader.Modo.APARTADA),
                    )
                    progreso.info(
                        "Descargados: ${r.descargados} · solo faltaba el like: ${r.soloLike} · ya tenían like: ${r.yaTenianLike} · " +
                            "no se bajan: ${r.saltados} · errores: ${r.errores}",
                    )
                }
            } catch (e: AgenteException) {
                progreso.error(e.message ?: e.toString())
            }
        }
    }
}
