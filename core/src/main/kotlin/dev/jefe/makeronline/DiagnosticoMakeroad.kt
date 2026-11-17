package dev.jefe.makeronline

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Diagnóstico de Makeroad (https://www.makeroad.com), antes de usar su subidor (SubidorMakeroad).
 *
 * Lo comprobado el 17/11/2026 con el Chrome de siempre de la persona (Claude in Chrome):
 *  - **No hay Cloudflare**: el servidor es nginx, sin cabeceras cf-ray ni página de verificación.
 *  - Pero la página carga el **captcha y la "huella de dispositivo" de Aliyun** (AliyunCaptcha.js,
 *    FeiLin y cloudauth-device.…aliyuncs.com). Eso puede detectar el Chrome que maneja la app.
 *
 * Así que lo que mira este programa es si **el Chrome de la app** llega al formulario de subida y
 * con la sesión iniciada, y si al guardar sale un captcha. Si sale, **no se salta**: lo resuelves tú y
 * queda apuntado (si sale siempre, hay que hacerlo con una extensión, como Printables).
 *
 * Pasos:
 *  0. Abre el formulario de subida y apunta si se ve, si hay sesión y si hay captcha a la vista.
 *     Sin tocar nada. Si con eso basta, escribe "parar" + ENTER y se guarda el informe.
 *  1. Si quieres (recomendado la primera vez), **tú subes un modelo a mano** y lo guardas con
 *     "Registrar" (= Guardar, va a borradores) mientras el programa apunta los campos y las
 *     peticiones a su API. No rellena ni pulsa nada.
 *
 * Resultado: diagnostico-makeroad.txt en la carpeta de datos (no se sube a git). No apunta cookies
 * ni valores de cabeceras (solo sus nombres), ni las respuestas de tu cuenta, credenciales o tokens;
 * en lo demás tapa firmas, claves y tokens (REDACTADO).
 *
 * Se maneja desde la consola: ENTER para pasar de paso, "parar" + ENTER para terminar y guardar lo visto.
 */
object DiagnosticoMakeroad {

    private const val WEB = "https://www.makeroad.com"
    private const val ESPERA_FORMULARIO_MS = 60_000L
    private const val ESPERA_MAX_MS = 20 * 60_000L
    private const val MAX_TEXTO = 20_000

    /** Peticiones cuya respuesta no se apunta: datos de tu cuenta, credenciales o tokens. */
    private val PRIVADAS = listOf("/user", "token", "login", "auth", "account", "message", "notice", "wallet", "address", "sts", "oss")

    /** Ruido que no interesa (estadísticas, anuncios). */
    private val RUIDO = listOf("google", "doubleclick", "facebook", "clarity", "sentry", "hotjar", "tiktok", "analytics", "/events", "_nuxt/")

    @JvmStatic
    fun main(args: Array<String>) {
        val rutas = Rutas.deLaApp() // la sesión de Chrome de la app
        val progreso = ProgresoConsola()
        try {
            AgentLock.acquire(rutas.candado, "Diagnóstico de Makeroad", false, progreso).use {
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
        val informe = StringBuilder("Diagnóstico de Makeroad · ${LocalDateTime.now()}\n")
        val red = Red()
        Files.createDirectories(rutas.perfilChrome)
        Browsers.playwright().use { pw ->
            Browsers.openChrome(pw, rutas.perfilChrome, 0.0).use { ctx ->
                try {
                    recorrer(ctx, red, informe, progreso, cancelacion)
                } catch (_: CanceladoException) {
                    informe.append("\n(Parado a petición tuya: se guarda lo visto hasta aquí)\n")
                } catch (e: Exception) {
                    val linea = e.message?.lineSequence()?.firstOrNull() ?: e.toString()
                    informe.append("\n!!! Se ha parado por un error: $linea\n")
                    progreso.error("Se ha parado por un error, pero guardo lo visto: $linea")
                }
                red.leerPendientes()
            }
        }
        seccion(informe, "PETICIONES (${red.apuntadas.size})", red.apuntadas.joinToString("\n"))
        val salida = rutas.datos.resolve("diagnostico-makeroad.txt")
        Files.writeString(salida, informe.toString())
        return salida
    }

    private fun recorrer(ctx: BrowserContext, red: Red, informe: StringBuilder, progreso: Progreso, cancelacion: Cancelacion) {
        ctx.setDefaultTimeout(30_000.0)
        ctx.onResponse { r -> red.nueva(r) }
        val page = ctx.pages().firstOrNull() ?: ctx.newPage()

        // --- 0. ¿Llega el Chrome de la app al formulario? ---
        progreso.paso("Abriendo el formulario de subida de Makeroad con el Chrome de la app")
        progreso.info("   Si sale un captcha o te pide iniciar sesión, hazlo tú en esa ventana. Espero hasta 1 minuto.")
        val primera = runCatching { page.navigate(SubidorMakeroad.FORMULARIO) }.getOrNull()
        informe.append("Primera respuesta de ${SubidorMakeroad.FORMULARIO}: HTTP ${primera?.status() ?: "?"}\n")
        val inicio = System.currentTimeMillis()
        val dentro = esperar(page, red, cancelacion, null, ESPERA_FORMULARIO_MS) { formularioVisible(page) }
        val segundos = (System.currentTimeMillis() - inicio) / 1000
        informe.append("Título de la página: \"${page.title()}\" · dirección: ${sinConsulta(page.url())}\n")
        informe.append("Captcha de Aliyun a la vista: ${if (captchaVisible(page)) "SÍ" else "no"}\n")
        informe.append("Webdriver (lo que ve la web de la automatización): ${runCatching { page.evaluate("() => navigator.webdriver") }.getOrNull()}\n")
        if (!dentro) {
            informe.append(
                "\n### RESULTADO: el Chrome de la app NO llega al formulario de subida en $segundos s.\n" +
                    "### Mira si pedía sesión o un captcha. Si es la web la que no lo deja, hay que hacerlo con una extensión.\n"
            )
            progreso.error("No se ve el formulario de subida tras $segundos s.")
        } else {
            informe.append("\n### RESULTADO: el Chrome de la app llega al formulario de subida (en $segundos s).\n")
            progreso.correcto("Se ve el formulario de subida de Makeroad ($segundos s).")
        }
        seccion(informe, "FORMULARIO VACÍO · CAMPOS", campos(page))

        // --- 1. Lo subes tú (opcional) ---
        progreso.info(
            "\n   Si con esto basta, escribe \"parar\" + ENTER y se guarda el informe.\n" +
                "   Si no: ① rellena tú el formulario con un modelo de prueba (archivos, 3 fotos, título,\n" +
                "   descripción, categoría, etiquetas, licencia) y marca \"Privado\". Sin guardar todavía.\n" +
                "   Cuando lo tengas, pulsa ENTER aquí."
        )
        esperar(page, red, cancelacion, null, ESPERA_MAX_MS) { false }
        informe.append("\nDirección: ${sinConsulta(page.url())}\n")
        seccion(informe, "FORMULARIO RELLENO · CAMPOS (tal como los has dejado)", campos(page))

        progreso.info(
            "\n   ② Ahora pulsa tú \"Registrar\" (= Guardar; va a borradores), NO \"Publicar\".\n" +
                "   Si sale un captcha, resuélvelo tú. Cuando esté guardado, pulsa ENTER aquí para terminar."
        )
        var vioCaptcha = false
        esperar(page, red, cancelacion, null, ESPERA_MAX_MS) {
            if (captchaVisible(page)) vioCaptcha = true
            false
        }
        page.waitForTimeout(2000.0)
        red.leerPendientes()
        informe.append("\nAl guardar ha salido el captcha de Aliyun: ${if (vioCaptcha) "SÍ" else "no"}\n")
        informe.append("Al terminar, la página está en: ${sinConsulta(page.url())}\n")
        informe.append("Avisos a la vista: ${runCatching { page.locator(".arco-message").allInnerTexts().joinToString(" | ") }.getOrDefault("")}\n")
    }

    /** El formulario está a la vista: el campo del título (el único input.arco-input, visto el 17/11/2026). */
    private fun formularioVisible(page: Page): Boolean = runCatching {
        page.locator("input.arco-input").locator("visible=true").count() > 0 &&
            page.locator("input[type=file]").count() >= 3
    }.getOrDefault(false)

    /** Si se ve el captcha de Aliyun (su ventana o su recuadro). */
    private fun captchaVisible(page: Page): Boolean = runCatching {
        page.evaluate(
            "() => [...document.querySelectorAll('[id*=aliyunCaptcha], [class*=aliyunCaptcha], iframe[src*=\"captcha\"]')]" +
                ".some(e => e.getClientRects().length > 0)"
        ) as Boolean
    }.getOrDefault(false)

    /**
     * Espera a que [listo] se cumpla, a un ENTER en la consola o a [maxMs]. Mientras, va leyendo
     * las respuestas (si no, se pierden al cambiar de página). true si se ha cumplido [listo].
     * Con [enter] = "" no se mira la consola.
     */
    private fun esperar(
        page: Page, red: Red, cancelacion: Cancelacion, enter: String?, maxMs: Long, listo: () -> Boolean,
    ): Boolean {
        val fin = System.currentTimeMillis() + maxMs
        while (System.currentTimeMillis() < fin) {
            cancelacion.comprobar()
            page.waitForTimeout(400.0) // deja a Playwright recibir lo que pasa en la página
            red.leerPendientes()
            if (runCatching(listo).getOrDefault(false)) return true
            if (enter == null && EntradaConsola.siguienteLinea(100) != null) return false
        }
        return false
    }

    // ----------------------------------------------------------------- red

    /** Las peticiones que interesan (su API y las subidas de archivos), ya en texto y sin datos privados. */
    private class Red {
        private val pendientes = ArrayList<Response>()
        val apuntadas = ArrayList<String>()
        private var numero = 0

        fun nueva(r: Response) {
            val url = r.url()
            val metodo = r.request().method()
            if (RUIDO.any { it in url }) return
            val api = "makeroad.com" in url && "/api/" in url
            val subida = metodo != "GET" && "makeroad.com" !in url // almacenamiento de archivos (OSS de Aliyun…)
            if (api || subida) pendientes += r
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
            val enviado = runCatching { pet.postData() ?: "" }.getOrDefault("")
            val privada = PRIVADAS.any { it in url.lowercase() || it in operacion(enviado).lowercase() }
            val sb = StringBuilder("#$n  ${pet.method()} ${sinConsulta(url)} → HTTP ${r.status()}  (${LocalDateTime.now().format(HORA)})\n")
            val op = operacion(enviado)
            if (op.isNotEmpty()) sb.append("   operación GraphQL: ").append(op).append('\n')
            sb.append("   cabeceras enviadas (solo nombres): ").append(pet.headers().keys.sorted().joinToString(", ")).append('\n')
            val tipo = pet.headers()["content-type"] ?: ""
            if (tipo.isNotEmpty()) sb.append("   content-type: ").append(tipo.substringBefore(";boundary").take(80)).append('\n')
            val bytes = runCatching { pet.postDataBuffer()?.size ?: 0 }.getOrDefault(0)
            if (bytes > 0) {
                val esTexto = "json" in tipo || "x-www-form-urlencoded" in tipo || tipo.startsWith("text")
                if (esTexto && !privada) sb.append("   enviado: ").append(tapar(enviado).take(MAX_TEXTO)).append('\n')
                else sb.append("   enviado: $bytes bytes").append(if ("multipart" in tipo) " (formulario con archivo)" else "").append('\n')
            }
            if (privada) {
                sb.append("   respuesta: (no se apunta: datos de tu cuenta o credenciales)\n")
            } else {
                val cuerpo = tapar(runCatching { r.text() }.getOrElse { "(no se ha podido leer)" })
                sb.append("   respuesta: ").append(cuerpo.take(MAX_TEXTO)).append(if (cuerpo.length > MAX_TEXTO) " (…cortado)" else "").append('\n')
            }
            return sb.toString()
        }

        /** El "operationName" de una petición GraphQL, si lo lleva. */
        private fun operacion(cuerpo: String): String =
            Regex("\"operationName\"\\s*:\\s*\"([^\"]+)\"").findAll(cuerpo).map { it.groupValues[1] }.joinToString(", ")
    }

    private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Tapa firmas, claves y tokens, y quita la parte "?…" de las direcciones (suele llevar firmas). */
    private fun tapar(texto: String): String = texto
        .replace(Regex("""(https?://[^"'\s?]+)\?[^"'\s]*"""), "$1?…")
        .replace(
            Regex("""(?i)("?(?:access_?key_?id|access_?key_?secret|security_?token|signature|token|authorization|password|credentials?|email)"?\s*[:=]\s*"?)[^"&,\s}]+"""),
            "$1REDACTADO",
        )

    private fun sinConsulta(url: String): String = url.substringBefore('?')

    // ----------------------------------------------------------------- página

    /**
     * Cada campo de la página: su "título" (el texto más cercano por encima), tipo, placeholder,
     * name, si está marcado y las clases de él y de sus contenedores. También botones y desplegables.
     * Sin suponer ninguna librería (no se sabe aún con qué está hecho el formulario).
     */
    private fun campos(page: Page): String = page.evaluate(
        """() => {
          const visible = e => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
          const clases = e => (typeof e.className === 'string' && e.className.trim()) ? '.' + e.className.trim().split(/\s+/).slice(0, 4).join('.') : '';
          const ruta = e => { const r = []; let p = e; for (let i = 0; i < 4 && p && p !== document.body; i++, p = p.parentElement) r.unshift(p.tagName.toLowerCase() + clases(p)); return r.join(' > '); };
          const titulo = e => {
            let p = e.parentElement;
            for (let i = 0; i < 8 && p; i++, p = p.parentElement) {
              const t = (p.innerText || '').trim().split('\n').map(s => s.trim()).filter(s => s && s !== '*')[0] || '';
              if (t && t !== (e.innerText || '').trim()) return t.slice(0, 70);
            }
            return '';
          };
          const main = document.querySelector('main') || document.body;
          const out = [];
          for (const e of main.querySelectorAll('input, textarea, [contenteditable=true], iframe, select')) {
            if (e.type === 'hidden') continue;
            const vis = visible(e) || e.type === 'file' || e.type === 'radio' || e.type === 'checkbox';
            if (!vis) continue;
            const marca = (e.type === 'radio' || e.type === 'checkbox')
              ? (e.checked ? ' [MARCADO]' : '') + ' value="' + e.value + '" opción="' + ((e.closest('label') || e.parentElement).innerText || '').trim().slice(0, 50) + '"'
              : '';
            const extra = [e.name ? 'name=' + e.name : '', e.placeholder ? 'placeholder="' + e.placeholder + '"' : '',
              e.accept ? 'accept="' + e.accept + '"' : '', e.multiple ? 'multiple' : '', e.maxLength > 0 ? 'maxlength=' + e.maxLength : '',
              e.id ? 'id=' + e.id : '', (e.value && e.type !== 'file' && e.type !== 'radio' && e.type !== 'checkbox') ? 'valor="' + String(e.value).slice(0, 60) + '"' : ''].filter(Boolean).join(' ');
            out.push('[' + titulo(e) + '] ' + e.tagName.toLowerCase() + (e.type ? ':' + e.type : '') + marca + ' ' + extra + '\n      ' + ruta(e));
          }
          out.push('\n--- Botones visibles');
          for (const b of main.querySelectorAll('button, [role=button]')) {
            if (!visible(b)) continue;
            const t = (b.innerText || b.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ');
            if (t) out.push('"' + t.slice(0, 50) + '"' + (b.disabled ? ' (desactivado)' : '') + (b.type ? ' type=' + b.type : '') + '   ' + ruta(b));
          }
          out.push('\n--- Desplegables y listas abiertas');
          out.push([...document.querySelectorAll('[role=listbox], [role=menu], [role=combobox]')].filter(visible)
            .map(e => ruta(e) + '  texto="' + (e.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 300) + '"').join('\n') || '(ninguno)');
          out.push('\n--- Mensajes de error / avisos');
          out.push([...document.querySelectorAll('[role=alert], [aria-invalid=true], .error, .invalid-feedback')].filter(visible)
            .map(e => (e.innerText || e.name || '').trim().slice(0, 100)).filter(Boolean).join(' | ') || '(ninguno)');
          return out.join('\n');
        }"""
    ) as String

    private fun seccion(informe: StringBuilder, titulo: String, contenido: String) {
        informe.append("\n========== $titulo ==========\n").append(contenido.trimEnd()).append('\n')
    }
}
