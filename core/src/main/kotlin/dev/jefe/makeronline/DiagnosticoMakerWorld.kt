package dev.jefe.makeronline

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Diagnóstico de la subida a MakerWorld, para preparar SubidorMakerWorld con datos reales
 * (ver docs/contexto-makerworld.md).
 *
 * Como en DiagnosticoCreality, **tú subes un modelo a mano** mientras el programa apunta lo que ve.
 * De MakerWorld todavía no se sabe nada comprobado (ni la dirección del formulario, ni sus pasos, ni
 * con qué está hecho), así que no se busca nada concreto: se hacen "capturas" cuando tú lo pides.
 *
 *  - Antes de abrir Chrome: qué llevan dentro los .3mf de pendientes/ (si son de Bambu Studio y
 *    para qué impresora), para saber cuáles pueden ir como perfil de impresión.
 *  - Al llegar al formulario: los nombres de las cookies de makerworld.com y bambulab.com (para saber
 *    cuál es la de la sesión) y los nombres del estado de la página (__NEXT_DATA__, __NUXT__…).
 *  - Cada ENTER: los campos de la página tal como están (etiquetas, tipos, accept de los archivos,
 *    marcados, clases), los botones, los textos de ayuda (formatos, proporciones, tamaños…) y los
 *    avisos, más una captura de pantalla.
 *  - "d" + ENTER: lo que haya en los desplegables abiertos (categorías, licencias…). Ábrelos tú antes.
 *  - Todo el rato: las peticiones a su API y las subidas de archivos, con lo que se envía y lo que
 *    responden (así se ve cómo confirma que se ha guardado y con qué id).
 *
 * El programa **no rellena ni pulsa nada**. Usa un modelo de prueba y déjalo en borrador o privado;
 * no lo publiques.
 *
 * Resultado: diagnostico-makerworld.txt y las capturas diagnostico-makerworld-<n>.png en la carpeta
 * de datos (no se suben a git). De las cookies solo se apuntan los nombres, nunca los valores; de las
 * cabeceras, solo los nombres. No se apunta lo que responden las peticiones de cuenta, sesión o
 * credenciales, y en lo demás se tapan firmas, claves, tokens y correos (REDACTADO).
 *
 * Se maneja desde la consola: ENTER = captura, "d" + ENTER = desplegables, "fin" + ENTER = terminar
 * y guardar, "parar" + ENTER = lo mismo que fin.
 */
object DiagnosticoMakerWorld {

    private const val WEB = "https://makerworld.com"
    /** En inglés, como en las otras webs: los textos de la web son los que usará el subidor. */
    private const val INICIO = "$WEB/en"
    private const val MAX_TEXTO = 20_000
    /** Cuánto se espera a cada orden de la consola antes de terminar solo. */
    private const val ESPERA_MAX_MS = 30 * 60_000L
    /** Cuántos .3mf de pendientes/ se miran como mucho (con unos pocos basta). */
    private const val MAX_3MF = 15

    private val JSON = ObjectMapper()
    private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val FECHA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

    /** Dominios de MakerWorld y de la cuenta de Bambu Lab (la sesión puede estar en cualquiera). */
    private val DOMINIOS = listOf("makerworld.com", "bambulab.com")

    /**
     * Palabras de la dirección de una petición cuya respuesta no se apunta: datos de tu cuenta,
     * credenciales o tokens. Se comparan palabras enteras ("sts", no "lists").
     */
    private val PRIVADAS = setOf(
        "token", "tokens", "login", "logout", "auth", "oauth", "account", "accounts", "credential", "credentials",
        "sts", "password", "profile", "wallet", "points", "coupon", "coupons", "message", "messages",
        "notification", "notifications", "privacy",
    )

    /** Ruido que no interesa (estadísticas, anuncios, avisos de errores). */
    private val RUIDO = listOf(
        "/cdn-cgi/", "google", "doubleclick", "facebook", "clarity", "sentry", "hotjar", "tiktok", "datadog",
        "segment", "mixpanel", "amplitude", "bytedance", "twitter", "reddit", "bing", "intercom",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        val rutas = Rutas.carpetaActual()
        val progreso = ProgresoConsola()
        try {
            AgentLock.acquire(rutas.candado, "Diagnóstico de MakerWorld", false, progreso).use {
                val informe = ejecutar(rutas, progreso)
                progreso.correcto("Informe guardado en ${informe.toAbsolutePath()}")
            }
        } catch (e: AgenteException) {
            progreso.error(e.message ?: e.toString())
            exitProcess(e.codigoSalida)
        }
        exitProcess(0) // el hilo que lee la consola no debe dejar el programa abierto
    }

    fun ejecutar(rutas: Rutas, progreso: Progreso): Path {
        val cancelacion = Cancelacion()
        EntradaConsola.iniciar(cancelacion, progreso)
        val informe = StringBuilder("Diagnóstico de la subida a MakerWorld · ${LocalDateTime.now()}\n")

        // Los .3mf se miran sin Chrome: así queda apuntado aunque luego falle la web
        progreso.paso("Mirando qué llevan dentro los .3mf de pendientes/")
        seccion(informe, "ARCHIVOS .3MF DE PENDIENTES (¿de Bambu Studio?, ¿qué impresora?)", archivos3mf(rutas))

        val red = Red()
        Files.createDirectories(rutas.perfilChrome)
        Browsers.playwright().use { pw ->
            Browsers.openChrome(pw, rutas.perfilChrome, 0.0).use { ctx ->
                try {
                    recorrer(ctx, red, informe, progreso, cancelacion, rutas.datos)
                } catch (_: CanceladoException) {
                    informe.append("\n(Parado a petición tuya: se guarda lo visto hasta aquí)\n")
                } catch (e: Exception) {
                    val linea = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
                    informe.append("\n!!! Se ha parado por un error: $linea\n")
                    progreso.error("Se ha parado por un error, pero guardo lo visto: $linea")
                }
                runCatching { red.leerPendientes() }
                // Las cookies otra vez al final: si has iniciado sesión durante el diagnóstico, aquí ya salen
                runCatching { seccion(informe, "COOKIES AL TERMINAR (solo nombres)", cookies(ctx)) }
            }
        }
        seccion(informe, "PETICIONES (${red.apuntadas.size})", red.apuntadas.joinToString("\n"))
        val salida = rutas.datos.resolve("diagnostico-makerworld.txt")
        Files.writeString(salida, informe.toString())
        return salida
    }

    private fun recorrer(
        ctx: BrowserContext, red: Red, informe: StringBuilder, progreso: Progreso, cancelacion: Cancelacion, datos: Path,
    ) {
        ctx.setDefaultTimeout(30_000.0)
        ctx.onResponse { r -> red.nueva(r) }
        val primera = ctx.pages().firstOrNull() ?: ctx.newPage()

        // --- 0. Llegar al formulario ---
        progreso.paso("Abriendo MakerWorld")
        primera.navigate(INICIO)
        progreso.info(
            "\n   ① En Chrome: si no has iniciado sesión en MakerWorld, inicia sesión.\n" +
                "   ② Ve a subir un modelo (el botón de subir de la web) hasta que se vea el formulario.\n" +
                "   Cuando estés en el formulario, pulsa ENTER aquí."
        )
        esperarOrden(ctx, red, cancelacion)
        val pagina = pagina(ctx)
        pagina.waitForTimeout(1500.0)
        informe.append("Dirección del formulario: ${sinConsulta(pagina.url())}\n")
        seccion(informe, "COOKIES AL LLEGAR AL FORMULARIO (solo nombres)", cookies(ctx))
        seccion(informe, "ESTADO DE LA PÁGINA (solo nombres)", estadoPagina(pagina))

        // --- 1. Capturas cuando tú lo pidas ---
        var n = 0
        n++
        capturar(informe, pagina(ctx), datos, n, abiertos = false)
        progreso.correcto("Captura $n apuntada (el formulario tal como sale)")
        progreso.info(
            "\n   Ahora sube un modelo de prueba a mano, paso a paso. En cada paso del formulario:\n" +
                "     · ENTER                → apunto los campos tal como están (y hago una captura de pantalla).\n" +
                "     · d + ENTER            → apunto los desplegables abiertos: abre antes el de categorías,\n" +
                "                              el de licencias… (uno cada vez).\n" +
                "     · fin + ENTER          → termino y guardo el informe.\n" +
                "   Prueba también: el .3mf como perfil de impresión y como archivo del modelo, un .stl, la portada\n" +
                "   y las fotos. Al final, guarda como BORRADOR si la web lo permite (si no, en privado) y haz una\n" +
                "   captura después de guardar, para ver la respuesta y la dirección. NO lo publiques."
        )
        while (true) {
            val orden = esperarOrden(ctx, red, cancelacion).trim().lowercase(Locale.ROOT)
            if (orden == "fin") break
            n++
            capturar(informe, pagina(ctx), datos, n, abiertos = orden == "d")
            red.leerPendientes()
            progreso.correcto("Captura $n apuntada" + if (orden == "d") " (desplegables)" else "")
        }

        val ultima = pagina(ctx)
        informe.append("\nAl terminar, la página está en: ${sinConsulta(ultima.url())}\n")
    }

    /** Apunta los campos (o los desplegables abiertos) y guarda una captura de pantalla. */
    private fun capturar(informe: StringBuilder, page: Page, datos: Path, n: Int, abiertos: Boolean) {
        val que = if (abiertos) "DESPLEGABLES ABIERTOS" else "CAMPOS"
        val contenido = runCatching { if (abiertos) desplegables(page) else campos(page) }
            .getOrElse { "(no se ha podido leer la página: ${it.message?.lineSequence()?.firstOrNull()})" }
        val imagen = datos.resolve("diagnostico-makerworld-$n.png")
        val conImagen = runCatching {
            page.screenshot(Page.ScreenshotOptions().setPath(imagen).setFullPage(true))
        }.isSuccess
        seccion(
            informe,
            "CAPTURA $n · $que · ${sinConsulta(page.url())}" + if (conImagen) " · ${imagen.fileName}" else "",
            contenido,
        )
    }

    /**
     * Espera a que escribas algo en la consola (ENTER solo = ""), mientras va leyendo las respuestas
     * (si no, se pierden al cambiar de página). Si en [ESPERA_MAX_MS] no escribes nada, es "fin".
     */
    private fun esperarOrden(ctx: BrowserContext, red: Red, cancelacion: Cancelacion): String {
        val fin = System.currentTimeMillis() + ESPERA_MAX_MS
        while (System.currentTimeMillis() < fin) {
            cancelacion.comprobar()
            pagina(ctx).waitForTimeout(400.0) // deja a Playwright recibir lo que pasa en la página
            red.leerPendientes()
            EntradaConsola.siguienteLinea(100)?.let { return it }
        }
        return "fin"
    }

    /** La pestaña en la que estás: la última abierta (la web puede abrir el formulario en otra). */
    private fun pagina(ctx: BrowserContext): Page = ctx.pages().lastOrNull { !it.isClosed } ?: ctx.newPage()

    // ----------------------------------------------------------------- .3mf

    /** Qué llevan dentro los .3mf de pendientes/ (sin abrir Chrome). */
    private fun archivos3mf(rutas: Rutas): String {
        val pendientes = rutas.pendientes
        if (!Files.isDirectory(pendientes)) return "(no existe ${pendientes.toAbsolutePath()})"
        val lista = Files.walk(pendientes, 2).use { s ->
            s.filter { Files.isRegularFile(it) && it.fileName.toString().lowercase(Locale.ROOT).endsWith(".3mf") }
                .sorted().toList()
        }
        if (lista.isEmpty()) return "(no hay ningún .3mf en pendientes/)"
        val resto = lista.size - MAX_3MF
        return lista.take(MAX_3MF).joinToString("\n") { describir3mf(pendientes, it) } +
            if (resto > 0) "\n(y $resto más sin mirar)" else ""
    }

    /**
     * Lo que dice un .3mf de sí mismo: qué archivos lleva dentro, los metadatos de 3D/3dmodel.model
     * (el programa que lo ha hecho suele estar en "Application") y lo que hay de impresora y versión en
     * los archivos de configuración de Metadata/. Solo se lee el principio de cada archivo.
     */
    private fun describir3mf(base: Path, archivo: Path): String {
        val sb = StringBuilder("--- ${base.relativize(archivo)}  (${Files.size(archivo) / 1024} KB)\n")
        try {
            ZipFile(archivo.toFile()).use { zip ->
                val nombres = zip.entries().toList().map { it.name }
                sb.append("   dentro (${nombres.size}): ").append(nombres.take(40).joinToString(", "))
                    .append(if (nombres.size > 40) ", …" else "").append('\n')
                fun principio(nombre: String, max: Int = 64 * 1024): String? = zip.getEntry(nombre)?.let { e ->
                    zip.getInputStream(e).use { String(it.readNBytes(max), Charsets.UTF_8) }
                }

                val modelo = principio("3D/3dmodel.model")
                if (modelo == null) {
                    sb.append("   (no tiene 3D/3dmodel.model)\n")
                } else {
                    val meta = Regex("""<metadata\s+name="([^"]+)"[^>]*>([^<]*)</metadata>""").findAll(modelo)
                        .map { "${it.groupValues[1]}=${it.groupValues[2].trim().take(80)}" }.toList()
                    sb.append("   3D/3dmodel.model · metadatos: ")
                        .append(meta.ifEmpty { listOf("(ninguno)") }.joinToString(" | ")).append('\n')
                }

                // Es un JSON entero: se lee completo (unos cientos de KB como mucho) para poder entenderlo
                val ajustes = principio("Metadata/project_settings.config", 8 * 1024 * 1024)
                if (ajustes == null) {
                    sb.append("   (no tiene Metadata/project_settings.config)\n")
                } else {
                    sb.append("   Metadata/project_settings.config: ").append(ajustesImpresora(ajustes)).append('\n')
                }

                for (nombre in listOf("Metadata/slice_info.config", "Metadata/model_settings.config")) {
                    val texto = principio(nombre) ?: continue
                    val pares = Regex("""key="([^"]+)"\s+value="([^"]*)"""").findAll(texto)
                        .map { "${it.groupValues[1]}=${it.groupValues[2].take(60)}" }.distinct().take(25).toList()
                    sb.append("   $nombre: ").append(pares.ifEmpty { listOf("(sin pares key/value)") }.joinToString(" | "))
                        .append('\n')
                }
            }
        } catch (e: Exception) {
            sb.append("   no se ha podido abrir como zip: ${e.message}\n")
        }
        return sb.toString()
    }

    /** De project_settings.config (JSON), solo lo que habla de impresora, boquilla, cama o versión. */
    private fun ajustesImpresora(texto: String): String {
        val j: JsonNode = try {
            JSON.readTree(texto)
        } catch (_: Exception) {
            return "(no es JSON)"
        }
        val interesa = Regex("printer|nozzle|bed_type|version|compatible|from", RegexOption.IGNORE_CASE)
        return j.fieldNames().asSequence().filter { interesa.containsMatchIn(it) }.take(30)
            .map { k ->
                val v = j.path(k)
                val valor = if (v.isArray) v.joinToString(";") { it.asText() } else v.asText()
                "$k=${valor.take(120)}"
            }.toList().ifEmpty { listOf("(nada de impresora ni versión)") }.joinToString(" | ")
    }

    // ----------------------------------------------------------------- sesión

    /**
     * Las cookies de MakerWorld y de Bambu Lab: nombre, dominio, si son httpOnly, cuándo caducan y lo
     * largo que es el valor (un token suele ser largo). **Nunca el valor.**
     */
    private fun cookies(ctx: BrowserContext): String = ctx.cookies()
        .filter { c -> DOMINIOS.any { c.domain.trimStart('.').endsWith(it) } }
        .sortedWith(compareBy({ it.domain }, { it.name }))
        .joinToString("\n") { c ->
            val caduca = if (c.expires == null || c.expires!! <= 0) "de sesión"
            else FECHA.format(Instant.ofEpochSecond(c.expires!!.toLong()))
            "${c.name}  dominio=${c.domain}  ruta=${c.path}  httpOnly=${c.httpOnly}  caduca=$caduca  " +
                "valor=${c.value?.length ?: 0} caracteres"
        }
        .ifEmpty { "(ninguna cookie de ${DOMINIOS.joinToString(" ni ")})" }

    /** Con qué está hecha la página, por los nombres de su estado (sin valores, que pueden tener datos tuyos). */
    private fun estadoPagina(page: Page): String = page.evaluate(
        """() => {
          const claves = o => o && typeof o === 'object' ? Object.keys(o).slice(0, 60).join(', ') : typeof o;
          const out = ['título: ' + document.title, 'idioma: ' + document.documentElement.lang];
          const nd = window.__NEXT_DATA__;
          if (nd) {
            out.push('__NEXT_DATA__: ' + claves(nd));
            out.push('  page: ' + nd.page);
            out.push('  props.pageProps: ' + claves(nd.props && nd.props.pageProps));
          } else out.push('(no hay __NEXT_DATA__)');
          out.push(window.__NUXT__ ? '__NUXT__: ' + claves(window.__NUXT__) : '(no hay __NUXT__)');
          out.push('otros globales: ' + Object.keys(window).filter(k => /^__|store|state/i.test(k)).slice(0, 40).join(', '));
          return out.join('\n');
        }"""
    ) as String

    // ----------------------------------------------------------------- red

    /** Las peticiones que interesan, ya convertidas en texto (sin datos privados). */
    private class Red {
        private val pendientes = ArrayList<Response>()
        val apuntadas = ArrayList<String>()
        private var numero = 0

        /**
         * De MakerWorld y Bambu Lab, las que hace la página por su cuenta (xhr/fetch): ahí está su API.
         * De otros sitios, solo las que envían algo (las subidas de archivos a su almacenamiento).
         */
        fun nueva(r: Response) {
            val url = r.url()
            if (RUIDO.any { it in url }) return
            val metodo = r.request().method()
            val tipo = r.request().resourceType()
            val suya = esSuya(url)
            if ((suya && (tipo == "xhr" || tipo == "fetch")) || (!suya && metodo != "GET" && metodo != "OPTIONS")) {
                pendientes += r
            }
        }

        /** Lee el cuerpo de las respuestas nuevas mientras siguen disponibles. */
        fun leerPendientes() {
            val copia = ArrayList(pendientes)
            pendientes.clear()
            for (r in copia) apuntadas += describir(r, ++numero)
        }

        private fun describir(r: Response, n: Int): String {
            val pet = r.request()
            val url = r.url()
            val privada = esPrivada(url)
            val sb = StringBuilder("#$n  ${pet.method()} ${sinConsulta(url)} → HTTP ${r.status()}  (${LocalDateTime.now().format(HORA)})\n")
            sb.append("   cabeceras enviadas (solo nombres): ")
                .append(pet.headers().keys.sorted().joinToString(", ")).append('\n')
            val tipo = pet.headers()["content-type"] ?: ""
            if (tipo.isNotEmpty()) sb.append("   content-type: ").append(tipo.substringBefore(";boundary").take(80)).append('\n')
            val bytes = runCatching { pet.postDataBuffer()?.size ?: 0 }.getOrDefault(0)
            if (bytes > 0) {
                val esTexto = "json" in tipo || "x-www-form-urlencoded" in tipo || tipo.startsWith("text")
                if (esTexto && !privada) {
                    sb.append("   enviado: ").append(tapar(pet.postData() ?: "").take(MAX_TEXTO)).append('\n')
                } else {
                    sb.append("   enviado: $bytes bytes").append(if ("multipart" in tipo) " (formulario con archivo)" else "").append('\n')
                }
            }
            if (privada) {
                sb.append("   respuesta: (no se apunta: datos de tu cuenta o credenciales)\n")
            } else {
                val cuerpo = runCatching { r.text() }.getOrElse { "(no se ha podido leer)" }
                val corto = tapar(cuerpo)
                sb.append("   respuesta: ").append(corto.take(MAX_TEXTO))
                    .append(if (corto.length > MAX_TEXTO) " (…cortado)" else "").append('\n')
            }
            return sb.toString()
        }
    }

    /** Si la dirección es de MakerWorld o de Bambu Lab. */
    private fun esSuya(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return false
        return DOMINIOS.any { host == it || host.endsWith(".$it") }
    }

    /** Si alguna palabra de la dirección (sin la parte "?…") es de cuenta, sesión o credenciales. */
    private fun esPrivada(url: String): Boolean =
        sinConsulta(url).lowercase(Locale.ROOT).split(Regex("[^a-z0-9]+")).any { it in PRIVADAS }

    /** Tapa firmas, claves, tokens y correos, y quita la parte "?…" de las direcciones (suele llevar firmas). */
    private fun tapar(texto: String): String = texto
        .replace(Regex("""(https?://[^"'\s?]+)\?[^"'\s]*"""), "$1?…")
        .replace(
            Regex("""(?i)("?(?:access_?key_?id|access_?key_?secret|security_?token|signature|token|authorization|password|credentials?|policy)"?\s*[:=]\s*"?)[^"&,\s}]+"""),
            "$1REDACTADO",
        )
        .replace(Regex("""[\w.+-]+@[\w-]+\.[\w.-]+"""), "REDACTADO@correo")

    private fun sinConsulta(url: String): String = url.substringBefore('?')

    // ----------------------------------------------------------------- página

    /**
     * Cada campo de la página: su "título" (aria-label, su label o el texto más cercano por encima), tipo,
     * accept (formatos de archivo), placeholder, si está marcado y las clases de él y de sus contenedores.
     * Como no se sabe con qué está hecho el formulario, se miran también los controles con role
     * (combobox, radio, checkbox, switch). Después: encabezados, botones, textos de ayuda y avisos.
     * Se deja fuera la cabecera y el pie de la web.
     */
    private fun campos(page: Page): String = page.evaluate(
        """() => {
          const visible = e => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
          const clases = e => (typeof e.className === 'string' && e.className.trim()) ? '.' + e.className.trim().split(/\s+/).slice(0, 5).join('.') : '';
          const ruta = e => { const r = []; let p = e; for (let i = 0; i < 4 && p && p !== document.body; i++, p = p.parentElement) r.unshift(p.tagName.toLowerCase() + clases(p)); return r.join(' > '); };
          const limpio = t => (t || '').trim().replace(/\s+/g, ' ');
          const titulo = e => {
            const a = e.getAttribute('aria-label'); if (a) return a.slice(0, 70);
            if (e.labels && e.labels.length) return limpio(e.labels[0].innerText).slice(0, 70);
            let p = e.parentElement;
            for (let i = 0; i < 8 && p; i++, p = p.parentElement) {
              const t = (p.innerText || '').trim().split('\n').map(s => s.trim()).filter(s => s && s !== '*')[0] || '';
              if (t && t !== (e.innerText || '').trim()) return t.slice(0, 70);
            }
            return '';
          };
          const fuera = e => !!e.closest('header, nav, footer');
          const out = ['--- Encabezados'];
          for (const h of document.querySelectorAll('h1, h2, h3, h4, [role=tab], [class*=step i]')) {
            if (!fuera(h) && visible(h) && limpio(h.innerText) && limpio(h.innerText).length < 120)
              out.push(h.tagName.toLowerCase() + clases(h) + (h.getAttribute('aria-selected') === 'true' ? ' [ACTIVO]' : '') + '  "' + limpio(h.innerText) + '"');
          }
          out.push('\n--- Campos');
          const sel = 'input, textarea, select, iframe, [contenteditable=true], [role=combobox], [role=radio], [role=checkbox], [role=switch], [role=textbox]';
          for (const e of document.querySelectorAll(sel)) {
            if (fuera(e) || e.type === 'hidden') continue;
            const especial = e.type === 'file' || e.type === 'radio' || e.type === 'checkbox';
            if (!visible(e) && !especial) continue;
            const role = e.getAttribute('role') || '';
            const marcable = e.type === 'radio' || e.type === 'checkbox' || ['radio', 'checkbox', 'switch'].includes(role);
            const marcado = e.checked === true || e.getAttribute('aria-checked') === 'true';
            const opcion = marcable
              ? ' opción="' + limpio((e.closest('label') || e.parentElement || e).innerText).slice(0, 60) + '"' + (marcado ? ' [MARCADO]' : '')
              : '';
            const extra = [e.name ? 'name=' + e.name : '', e.id ? 'id=' + e.id : '', role ? 'role=' + role : '',
              e.placeholder ? 'placeholder="' + e.placeholder + '"' : '', e.accept ? 'accept="' + e.accept + '"' : '',
              e.multiple ? 'multiple' : '', e.maxLength > 0 ? 'maxlength=' + e.maxLength : '',
              e.disabled ? 'desactivado' : '', (e.value && !marcable && e.type !== 'file') ? 'valor="' + String(e.value).slice(0, 40) + '"' : ''
            ].filter(Boolean).join(' ');
            out.push('[' + titulo(e) + '] ' + e.tagName.toLowerCase() + (e.type ? ':' + e.type : '') + opcion + ' ' + extra + '\n      ' + ruta(e));
          }
          out.push('\n--- Botones visibles');
          for (const b of document.querySelectorAll('button, [role=button]')) {
            if (fuera(b) || !visible(b)) continue;
            const t = limpio(b.innerText || b.getAttribute('aria-label'));
            if (t) out.push('"' + t.slice(0, 50) + '"' + (b.disabled ? ' (desactivado)' : '') + '   ' + ruta(b));
          }
          out.push('\n--- Textos de ayuda (formatos, proporciones, tamaños, borrador, privado, licencia, impresora…)');
          const ayuda = /3mf|stl|step|\.zip|ratio|\b\d+\s*[:x×]\s*\d+\b|\bmb\b|\bpx\b|draft|private|public|visib|licen|bambu studio|printer|profile|cover|max|limit|required|original|remix|nsfw|exclusive/i;
          const vistos = new Set();
          for (const e of document.querySelectorAll('body *')) {
            if (vistos.size >= 120) break;
            if (fuera(e) || !visible(e) || e.children.length > 2) continue;
            const t = limpio(e.innerText);
            if (!t || t.length > 220 || vistos.has(t) || !ayuda.test(t)) continue;
            vistos.add(t);
            out.push('"' + t + '"   ' + e.tagName.toLowerCase() + clases(e));
          }
          out.push('\n--- Avisos y errores');
          const avisos = [...document.querySelectorAll('[role=alert], [class*=error i], [class*=warning i], [class*=toast i], [class*=message i]')]
            .filter(e => visible(e) && !fuera(e)).map(e => limpio(e.innerText)).filter(t => t && t.length < 300);
          out.push([...new Set(avisos)].join(' | ') || '(ninguno)');
          return out.join('\n');
        }"""
    ) as String

    /** Lo que hay en los desplegables, menús y ventanas abiertos ahora mismo (sin tocar nada). */
    private fun desplegables(page: Page): String = page.evaluate(
        """() => {
          const visible = e => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none';
          const clases = e => (typeof e.className === 'string' && e.className.trim()) ? '.' + e.className.trim().split(/\s+/).slice(0, 5).join('.') : '';
          const estructura = (e, n) => n > 5 ? '' : '  '.repeat(n) + e.tagName.toLowerCase() + clases(e)
              + (e.getAttribute('role') ? ' role=' + e.getAttribute('role') : '')
              + (e.dataset && Object.keys(e.dataset).length ? ' data-' + Object.keys(e.dataset).slice(0, 4).join(' data-') : '') + '\n'
              + [...e.children].slice(0, 3).map(h => estructura(h, n + 1)).join('');
          const sel = '[role=listbox], [role=menu], [role=dialog], [role=tree], [role=tooltip], [class*=dropdown i], [class*=popover i], '
            + '[class*=popper i], [class*=cascader i], [class*=modal i], [data-radix-popper-content-wrapper]';
          const todos = [...document.querySelectorAll(sel)]
            .filter(e => visible(e) && !e.closest('header, nav') && (e.innerText || '').trim());
          // Solo los de fuera: si uno está dentro de otro de la lista, ya sale con él
          const raiz = todos.filter(e => !todos.some(o => o !== e && o.contains(e)));
          return raiz.map(e => '--- ' + e.tagName.toLowerCase() + clases(e) + (e.getAttribute('role') ? ' role=' + e.getAttribute('role') : '')
              + '\n' + e.innerText.slice(0, 6000) + '\n\n(estructura)\n' + estructura(e, 0)).join('\n')
            || '(no se ve ningún desplegable abierto: ábrelo en Chrome y vuelve a escribir d + ENTER)';
        }"""
    ) as String

    private fun seccion(informe: StringBuilder, titulo: String, contenido: String) {
        informe.append("\n========== $titulo ==========\n").append(contenido.trimEnd()).append('\n')
    }
}
