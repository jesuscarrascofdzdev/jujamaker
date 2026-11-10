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
 * Diagnóstico de Printables, antes de hacer su subidor.
 *
 * Lo primero que mira es si Printables deja entrar al Chrome que maneja la app. MakerWorld no lo
 * deja (Cloudflare se queda en bucle, comprobado el 10/11/2026) y Printables tiene una verificación
 * aún más dura: el 10/11/2026 ni el navegador de Claude pasaba de "Verificación de seguridad en
 * curso" (HTTP 403). Si aquí tampoco pasa, no se puede subir como a Creality y hay que hacerlo como
 * MakerWorld (extensión en tu Chrome de siempre). La verificación **no se salta**: si sale una
 * casilla, la marcas tú; el programa solo mira si se llega a la web.
 *
 * Si se llega, sigue como el diagnóstico de Creality: inicias sesión, vas al formulario de subida y
 * **tú subes un modelo a mano** (como borrador o privado) mientras el programa apunta los campos de
 * la página y las peticiones a su API. No rellena ni pulsa nada.
 *
 * Resultado: diagnostico-printables.txt en la carpeta de datos (no se sube a git). No apunta
 * cookies ni valores de cabeceras (solo sus nombres), ni las respuestas de tu cuenta, credenciales
 * o tokens; en lo demás tapa firmas, claves y tokens (REDACTADO).
 *
 * Se maneja desde la consola: ENTER para pasar de paso, "parar" + ENTER para terminar y guardar lo visto.
 */
object DiagnosticoPrintables {

    private const val WEB = "https://www.printables.com"
    private const val ESPERA_CLOUDFLARE_MS = 3 * 60_000L
    private const val ESPERA_MAX_MS = 20 * 60_000L
    private const val MAX_TEXTO = 20_000

    /** Peticiones cuya respuesta no se apunta: datos de tu cuenta, credenciales o tokens. */
    private val PRIVADAS = listOf("account", "token", "login", "auth", "me\"", "notification", "message", "wallet", "prusameter")

    /** Ruido que no interesa (estadísticas, Cloudflare). */
    private val RUIDO = listOf("/cdn-cgi/", "google", "doubleclick", "facebook", "clarity", "sentry", "hotjar", "tiktok", "analytics")

    @JvmStatic
    fun main(args: Array<String>) {
        val rutas = Rutas.carpetaActual()
        val progreso = ProgresoConsola()
        try {
            AgentLock.acquire(rutas.candado, "Diagnóstico de Printables", false, progreso).use {
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
        val informe = StringBuilder("Diagnóstico de Printables · ${LocalDateTime.now()}\n")
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
        val salida = rutas.datos.resolve("diagnostico-printables.txt")
        Files.writeString(salida, informe.toString())
        return salida
    }

    private fun recorrer(ctx: BrowserContext, red: Red, informe: StringBuilder, progreso: Progreso, cancelacion: Cancelacion) {
        ctx.setDefaultTimeout(30_000.0)
        ctx.onResponse { r -> red.nueva(r) }
        val page = ctx.pages().firstOrNull() ?: ctx.newPage()

        // --- 0. ¿Deja pasar Cloudflare? ---
        progreso.paso("Abriendo Printables con el Chrome de la app")
        progreso.info(
            "   Si sale una casilla de \"verifica que eres humano\", márcala tú. Espero hasta 3 minutos\n" +
                "   a que se vea la web de Printables."
        )
        val primera = runCatching { page.navigate(WEB) }.getOrNull()
        informe.append("Primera respuesta de $WEB: HTTP ${primera?.status() ?: "?"}\n")
        val inicio = System.currentTimeMillis()
        val dentro = esperar(page, red, cancelacion, null, ESPERA_CLOUDFLARE_MS) { !enVerificacion(page) }
        val segundos = (System.currentTimeMillis() - inicio) / 1000
        informe.append("Título de la página: \"${page.title()}\" · dirección: ${sinConsulta(page.url())}\n")
        if (!dentro) {
            informe.append(
                "\n### RESULTADO: Cloudflare NO deja pasar al Chrome de la app (sigue en la verificación tras $segundos s).\n" +
                    "### Printables no se puede subir como Creality: hay que hacerlo con la extensión, como MakerWorld.\n"
            )
            progreso.error("Cloudflare no deja pasar: Printables sigue en la verificación tras $segundos s.")
            return
        }
        informe.append("\n### RESULTADO: Cloudflare deja pasar al Chrome de la app (en $segundos s).\n")
        progreso.correcto("Cloudflare deja pasar: se ve la web de Printables ($segundos s).")

        // --- 1. Sesión y formulario ---
        progreso.info(
            "\n   ① Si no has iniciado sesión en Printables en esta ventana, inicia sesión ahora.\n" +
                "   ② Ve al formulario para subir un modelo (botón de subir / \"Upload\").\n" +
                "   Cuando estés en el formulario, pulsa ENTER aquí."
        )
        esperar(page, red, cancelacion, null, ESPERA_MAX_MS) { false }
        page.waitForTimeout(2000.0)
        if (enVerificacion(page)) informe.append("\n(Al ir al formulario ha vuelto a salir la verificación de Cloudflare)\n")
        informe.append("\nDirección del formulario: ${sinConsulta(page.url())}\n")
        seccion(informe, "FORMULARIO VACÍO · CAMPOS", campos(page))

        // --- 2. Lo rellenas tú ---
        progreso.info(
            "\n   ③ Rellena tú el formulario con un modelo de prueba: archivos, imágenes, nombre, categoría,\n" +
                "      etiquetas, licencia, descripción… Déjalo como borrador o privado.\n" +
                "   Cuando lo tengas (sin publicar), pulsa ENTER aquí."
        )
        esperar(page, red, cancelacion, null, ESPERA_MAX_MS) { false }
        informe.append("\nDirección: ${sinConsulta(page.url())}\n")
        seccion(informe, "FORMULARIO RELLENO · CAMPOS (tal como los has dejado)", campos(page))

        // --- 3. Guardar ---
        progreso.info(
            "\n   ④ Ahora guárdalo tú como borrador (o privado), sin publicar.\n" +
                "   Cuando esté guardado, pulsa ENTER aquí para terminar."
        )
        esperar(page, red, cancelacion, null, ESPERA_MAX_MS) { false }
        page.waitForTimeout(2000.0)
        red.leerPendientes()
        informe.append("\nAl terminar, la página está en: ${sinConsulta(page.url())}\n")
    }

    /**
     * Si la página es la verificación de Cloudflare: su título es "Just a moment..." o, con Chrome en
     * español, "Un momento…" (comprobado el 10/11/2026), o lleva su casilla (challenges.cloudflare.com).
     */
    private fun enVerificacion(page: Page): Boolean {
        val titulo = runCatching { page.title() }.getOrDefault("")
        if (titulo.startsWith("Just a moment") || titulo.startsWith("Un momento")) return true
        return runCatching {
            page.evaluate("() => !!document.querySelector('iframe[src*=\"challenges.cloudflare.com\"], #challenge-form, #cf-chl-widget')") as Boolean
        }.getOrDefault(true)
    }

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
            val api = "printables.com" in url && ("graphql" in url || "/api/" in url)
            val subida = metodo != "GET" && "printables.com" !in url // almacenamiento de archivos, CDN…
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
