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
 * Diagnóstico de la subida a Creality Cloud, para preparar el subidor con datos reales.
 *
 * Abre el formulario de subida de Creality Cloud con tu sesión (Chrome a la vista) y **tú subes
 * un modelo a mano** mientras el programa apunta:
 *  - los campos de cada paso del formulario (etiquetas, tipos, placeholders, clases);
 *  - lo que sale al abrir el desplegable de categorías;
 *  - todas las peticiones a su API y las subidas de archivos (también las que van a Aliyun),
 *    con lo que se envía y lo que responden.
 *
 * El programa **no rellena ni pulsa nada del formulario** (salvo abrir y cerrar el desplegable de
 * categorías para verlo). Lo de subir y "Entregar" lo haces tú. Usa "Solo yo" en Visibilidad.
 *
 * Resultado: diagnostico-creality.txt en la carpeta de datos (no se sube a git).
 * No apunta cookies ni valores de cabeceras (solo sus nombres), ni lo que responden las peticiones
 * de tu cuenta, credenciales o tokens; en lo demás tapa firmas, claves y tokens (REDACTADO).
 *
 * Se maneja desde la consola: ENTER para pasar de paso, "parar" + ENTER para terminar y guardar lo visto.
 */
object DiagnosticoCreality {

    private const val WEB = "https://www.crealitycloud.com"
    /** En inglés, como MakerOnline: los textos de la web son los que usará el subidor. */
    private const val FORMULARIO = "$WEB/en/create-model"
    private const val MAX_TEXTO = 20_000
    private const val ESPERA_MAX_MS = 20 * 60_000L

    /** Peticiones cuya respuesta no se apunta: datos de tu cuenta, credenciales o tokens. */
    private val PRIVADAS = listOf("aliyun", "account", "token", "login", "/user/", "privacy", "coin", "gift", "msg/", "im/")

    /** Ruido que no interesa (estadísticas). */
    private val RUIDO = listOf("/cdn-cgi/", "google", "doubleclick", "facebook", "clarity", "sentry", "hotjar", "tiktok")

    @JvmStatic
    fun main(args: Array<String>) {
        val rutas = Rutas.carpetaActual()
        val progreso = ProgresoConsola()
        try {
            AgentLock.acquire(rutas.candado, "Diagnóstico de Creality", false, progreso).use {
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
        val informe = StringBuilder("Diagnóstico de la subida a Creality Cloud · ${LocalDateTime.now()}\n")
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
        val salida = rutas.datos.resolve("diagnostico-creality.txt")
        Files.writeString(salida, informe.toString())
        return salida
    }

    private fun recorrer(ctx: BrowserContext, red: Red, informe: StringBuilder, progreso: Progreso, cancelacion: Cancelacion) {
        ctx.setDefaultTimeout(30_000.0)
        ctx.onResponse { r -> red.nueva(r) }
        val page = ctx.pages().firstOrNull() ?: ctx.newPage()

        // --- 0. Sesión ---
        progreso.paso("Abriendo el formulario de subida de Creality Cloud")
        page.navigate(FORMULARIO)
        esperar(page, red, cancelacion, "") { haySesion(page) && "/create-model" in page.url() }.let { listo ->
            if (!listo) {
                progreso.info(
                    "   Si no has iniciado sesión en Creality Cloud en esta ventana de Chrome, inicia sesión ahora\n" +
                        "   y vuelve a \"Upload\" → subir modelo ($FORMULARIO). Sigo solo cuando estés ahí."
                )
                if (!esperar(page, red, cancelacion, null, ESPERA_MAX_MS) { haySesion(page) && "/create-model" in page.url() }) {
                    throw AgenteException("No has llegado al formulario de subida con la sesión iniciada.")
                }
            }
        }
        page.waitForTimeout(2000.0)
        informe.append("Dirección del formulario: ${sinConsulta(page.url())}\n")
        seccion(informe, "window.__NUXT__ (solo los nombres, sin valores)", estructuraNuxt(page))

        // --- 1. Paso 1 ---
        progreso.paso("Paso 1: campos")
        seccion(informe, "PASO 1 · CAMPOS", campos(page))
        progreso.info(
            "\n   ① En Chrome: elige el tipo de archivo, sube el .3mf y espera a que termine (también la conversión).\n" +
                "   ② Pulsa el botón para pasar al paso 2.\n" +
                "   ③ Rellena todo el paso 2 (nombre, categoría, etiquetas, portadas, licencia, descripción…),\n" +
                "      con Visibilidad = Solo yo. NO pulses todavía el botón de enviar.\n" +
                "   Cuando lo tengas, pulsa ENTER aquí."
        )
        esperar(page, red, cancelacion, null, ESPERA_MAX_MS) { false }

        // --- 2. Paso 2 ---
        progreso.paso("Paso 2: campos")
        seccion(informe, "PASO 2 · CAMPOS (tal como los has dejado)", campos(page))
        seccion(informe, "PASO 2 · EDITORES DE TEXTO (TinyMCE)", editores(page))
        progreso.paso("Paso 2: desplegable de categorías")
        seccion(informe, "PASO 2 · DESPLEGABLE DE CATEGORÍAS (al abrirlo)", desplegableCategorias(page))

        // --- 3. Enviar ---
        progreso.info(
            "\n   ④ Ahora pulsa tú el botón de enviar en Chrome (comprueba antes: Visibilidad = Solo yo).\n" +
                "   Sigo solo cuando la página salte a la del modelo, o pulsa ENTER si quieres terminar sin enviar."
        )
        val enviado = esperar(page, red, cancelacion, null, ESPERA_MAX_MS) { "/model-detail/" in page.url() }
        if (enviado) {
            page.waitForTimeout(3000.0) // las peticiones de la página nueva
            red.leerPendientes()
            informe.append("\nDespués de enviar, la página ha saltado a: ${sinConsulta(page.url())}\n")
        } else {
            informe.append("\n(Terminado sin enviar)\n")
        }
        val avisos = page.locator(".el-message, .el-notification").allInnerTexts().joinToString(" | ")
        if (avisos.isNotBlank()) informe.append("Avisos en pantalla: $avisos\n")
    }

    /**
     * Espera a que [listo] se cumpla, a un ENTER en la consola o a [maxMs]. Mientras, va leyendo
     * las respuestas (si no, se pierden al cambiar de página). true si se ha cumplido [listo].
     * Con [enter] = "" no se mira la consola (primer vistazo: 5 s).
     */
    private fun esperar(
        page: Page, red: Red, cancelacion: Cancelacion, enter: String?, maxMs: Long = 5_000L, listo: () -> Boolean,
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

    private fun haySesion(page: Page): Boolean = page.evaluate(
        "() => { const s = window.__NUXT__ && window.__NUXT__.state; return !!(s && (s['\$sisLogin'] === true || s.isLogin === true)); }"
    ) as Boolean

    // ----------------------------------------------------------------- red

    /** Las peticiones que interesan, ya convertidas en texto (sin datos privados). */
    private class Red {
        private val pendientes = ArrayList<Response>()
        val apuntadas = ArrayList<String>()
        private var numero = 0

        fun nueva(r: Response) {
            val url = r.url()
            val metodo = r.request().method()
            if (RUIDO.any { it in url }) return
            val api = url.startsWith(WEB) && "/api/" in url
            val subida = metodo != "GET" && !url.startsWith(WEB) // Aliyun, CDN de imágenes…
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
            val privada = PRIVADAS.any { it in url.lowercase() }
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

    private val HORA = DateTimeFormatter.ofPattern("HH:mm:ss")

    /** Tapa firmas, claves y tokens, y quita la parte "?…" de las direcciones (suele llevar firmas). */
    private fun tapar(texto: String): String = texto
        .replace(Regex("""(https?://[^"'\s?]+)\?[^"'\s]*"""), "$1?…")
        .replace(
            Regex("""(?i)("?(?:access_?key_?id|access_?key_?secret|security_?token|signature|token|authorization|password|credentials?)"?\s*[:=]\s*"?)[^"&,\s}]+"""),
            "$1REDACTADO",
        )

    private fun sinConsulta(url: String): String = url.substringBefore('?')

    // ----------------------------------------------------------------- página

    /**
     * Cada campo del formulario: su "título" (el texto más cercano por encima), tipo, placeholder,
     * si está marcado y las clases de él y de sus contenedores. También botones y zonas de subida.
     */
    private fun campos(page: Page): String = page.evaluate(
        """() => {
          const visible = e => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden';
          const clases = e => (typeof e.className === 'string' && e.className.trim()) ? '.' + e.className.trim().split(/\s+/).join('.') : '';
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
              ? (e.closest('.is-checked') ? ' [MARCADO]' : '') + ' opción="' + ((e.closest('label') || e.parentElement.parentElement).innerText || '').trim().slice(0, 50) + '"'
              : '';
            const extra = [e.placeholder ? 'placeholder="' + e.placeholder + '"' : '', e.accept ? 'accept="' + e.accept + '"' : '',
              e.multiple ? 'multiple' : '', e.maxLength > 0 ? 'maxlength=' + e.maxLength : '', e.id ? 'id=' + e.id : ''].filter(Boolean).join(' ');
            out.push('[' + titulo(e) + '] ' + e.tagName.toLowerCase() + (e.type ? ':' + e.type : '') + marca + ' ' + extra + '\n      ' + ruta(e));
          }
          out.push('\n--- Botones visibles');
          for (const b of main.querySelectorAll('button, [role=button]')) {
            if (!visible(b)) continue;
            const t = (b.innerText || b.getAttribute('aria-label') || '').trim().replace(/\s+/g, ' ');
            if (t) out.push('"' + t.slice(0, 50) + '"' + (b.disabled ? ' (desactivado)' : '') + '   ' + ruta(b));
          }
          out.push('\n--- Zonas de subida (.el-upload)');
          for (const u of main.querySelectorAll('.el-upload')) {
            out.push('[' + titulo(u) + '] ' + ruta(u) + '  texto="' + (u.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 80) + '"');
          }
          out.push('\n--- Mensajes de error del formulario');
          out.push([...main.querySelectorAll('.el-form-item__error, .el-message')].map(e => e.innerText.trim()).join(' | ') || '(ninguno)');
          return out.join('\n');
        }"""
    ) as String

    /** Los editores TinyMCE: cuántos hay, su id y el principio de su texto. */
    private fun editores(page: Page): String = page.evaluate(
        """() => {
          if (!window.tinymce) return '(no hay window.tinymce)';
          const lista = (tinymce.get && tinymce.get()) || tinymce.editors || [];
          return [...lista].map(e => 'id=' + e.id + '  iframe=' + (e.iframeElement ? e.iframeElement.id : '?')
            + '  contenedor=' + (e.getContainer() ? e.getContainer().className : '?')
            + '\n   texto: ' + e.getContent({format: 'text'}).slice(0, 200).replace(/\n/g, ' ')
            + '\n   html: ' + e.getContent().slice(0, 300)).join('\n') || '(ninguno)';
        }"""
    ) as String

    /** Abre el desplegable de categorías, apunta lo que sale y lo cierra sin elegir nada. */
    private fun desplegableCategorias(page: Page): String {
        val campo = page.locator("input[placeholder*='categor' i]").first()
        if (campo.count() == 0) return "(no he encontrado el campo de categoría: busca \"categor\" en los campos de arriba)"
        return try {
            campo.scrollIntoViewIfNeeded()
            campo.click()
            page.waitForTimeout(1500.0)
            val visto = page.evaluate(
                """() => {
                  const visible = e => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none';
                  const clases = e => (typeof e.className === 'string' && e.className.trim()) ? '.' + e.className.trim().split(/\s+/).join('.') : '';
                  const estructura = (e, n) => n > 5 ? '' : '  '.repeat(n) + e.tagName.toLowerCase() + clases(e) + '\n'
                      + [...e.children].slice(0, 3).map(h => estructura(h, n + 1)).join('');
                  return [...document.querySelectorAll('.el-popper, .el-cascader__dropdown, .el-cascader-panel, .el-select-dropdown, [role=listbox], [role=menu], .el-autocomplete-suggestion')]
                    .filter(visible)
                    .map(e => '--- ' + clases(e) + '\n' + e.innerText.slice(0, 4000) + '\n\n(estructura)\n' + estructura(e, 0))
                    .join('\n') || '(no se ve ningún desplegable)';
                }"""
            ) as String
            page.keyboard().press("Escape")
            page.waitForTimeout(500.0)
            visto
        } catch (e: Exception) {
            "(no se ha podido abrir: ${e.message?.lineSequence()?.firstOrNull()})"
        }
    }

    /** Los nombres de lo que hay en window.__NUXT__ (sin los valores, que pueden tener datos de tu cuenta). */
    private fun estructuraNuxt(page: Page): String = page.evaluate(
        """() => {
          const n = window.__NUXT__;
          if (!n) return '(no hay window.__NUXT__)';
          const claves = o => o && typeof o === 'object' ? Object.keys(o).join(', ') : typeof o;
          return 'arriba: ' + claves(n) + '\ndata: ' + claves(n.data) + '\nstate: ' + claves(n.state);
        }"""
    ) as String

    private fun seccion(informe: StringBuilder, titulo: String, contenido: String) {
        informe.append("\n========== $titulo ==========\n").append(contenido.trimEnd()).append('\n')
    }
}
