package dev.jefe.makeronline

import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Locator
import com.microsoft.playwright.Page
import com.microsoft.playwright.Response
import com.microsoft.playwright.TimeoutError
import com.microsoft.playwright.options.AriaRole
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import kotlin.system.exitProcess

/**
 * Diagnóstico del formulario de subida, para hacer los desplegables del editor (fase 3c) con
 * las opciones reales de MakerOnline: licencias, categorías (2 niveles) y tipo de impresión.
 *
 * Abre /en/upload con tu sesión (Chrome a la vista), abre cada desplegable y apunta lo que sale
 * y de qué peticiones de la web salen los datos. **No sube ni guarda nada**: cierra la pestaña
 * sin pulsar "Save to draft".
 *
 * Resultado: diagnostico-formulario.txt en la carpeta de datos (no se sube a git). No apunta
 * cookies, cabeceras ni tokens, ni el contenido de las peticiones de tu cuenta (las que tienen
 * "user" en la dirección).
 *
 * Las clases de Element UI que usa (.el-cascader-menu, .el-popper…) son solo para mirar: si no
 * aparecen, el informe lo dice y queda la estructura de lo que sí hay.
 */
object DiagnosticoFormulario {

    private const val WEB = Api.WEB
    private const val MAX_CUERPO = 30_000

    @JvmStatic
    fun main(args: Array<String>) {
        val rutas = Rutas.carpetaActual()
        val progreso = ProgresoConsola()
        try {
            AgentLock.acquire(rutas.candado, "Diagnóstico del formulario", false, progreso).use {
                val informe = ejecutar(rutas, progreso)
                progreso.correcto("Informe guardado en ${informe.toAbsolutePath()}")
            }
        } catch (e: AgenteException) {
            progreso.error(e.message ?: e.toString())
            exitProcess(e.codigoSalida)
        }
    }

    fun ejecutar(rutas: Rutas, progreso: Progreso): Path {
        if (!Files.isDirectory(rutas.perfilChrome)) {
            throw AgenteException("No hay sesión guardada. Inicia sesión primero (app: pantalla Sesión).")
        }
        val texto = Browsers.playwright().use { pw ->
            Browsers.openChrome(pw, rutas.perfilChrome, 0.0).use { ctx -> informe(ctx, progreso) }
        }
        val salida = rutas.datos.resolve("diagnostico-formulario.txt")
        Files.writeString(salida, texto)
        return salida
    }

    /** El informe completo, con un Chrome ya abierto con la sesión. */
    fun informe(ctx: BrowserContext, progreso: Progreso): String {
        val informe = StringBuilder("Diagnóstico del formulario de subida · ${LocalDateTime.now()}\n")
        val respuestas = ArrayList<Response>()
        ctx.setDefaultTimeout(30_000.0)
        val page = ctx.newPage()
        page.onResponse { r -> if (r.url().startsWith(WEB) && "/api/" in r.url()) respuestas += r }

        try {
            revisarFormulario(page, informe, progreso)
        } catch (e: AgenteException) {
            throw e
        } catch (e: Exception) {
            // Lo que se haya visto hasta aquí se guarda igual
            informe.append("\n!!! Se ha parado por un error: ${e.message?.lineSequence()?.firstOrNull() ?: e}\n")
            progreso.error("Se ha parado por un error, pero guardo lo visto: ${e.message?.lineSequence()?.firstOrNull()}")
        }

        // --- 4. Peticiones de la web ---
        progreso.paso("Peticiones de la web")
        val red = StringBuilder()
        for (r in respuestas) {
            val direccion = r.url().removePrefix(WEB)
            red.append("${r.request().method()} $direccion → HTTP ${r.status()}\n")
            val privada = listOf("user", "token", "login", "account").any { it in direccion.lowercase() }
            if (!privada) {
                val cuerpo = try { r.text() } catch (_: Exception) { "(no se ha podido leer)" }
                red.append(cuerpo.take(MAX_CUERPO)).append(if (cuerpo.length > MAX_CUERPO) "\n(…cortado)\n" else "\n")
            }
            red.append('\n')
        }
        seccion(informe, "PETICIONES A LA API (sin contenido las de tu cuenta)", red.toString())
        page.close() // sin guardar nada
        return informe.toString()
    }

    /** Abre el formulario y apunta lo que ofrece cada desplegable. */
    private fun revisarFormulario(page: Page, informe: StringBuilder, progreso: Progreso) {
        progreso.paso("Abriendo el formulario")
        page.navigate("$WEB/en/upload")
        val licencia = page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("Choose a CC license"))
        try {
            licencia.waitFor(Locator.WaitForOptions().setTimeout(15_000.0))
        } catch (e: TimeoutError) {
            throw AgenteException("No se ha cargado el formulario de subida. ¿Ha caducado la sesión?")
        }
        cerrarCookies(page)
        seccion(informe, "window.__NUXT__ (solo los nombres, sin valores)", estructuraNuxt(page))

        // --- 1. Licencias ---
        progreso.paso("Licencias")
        licencia.click()
        page.waitForTimeout(1500.0)
        seccion(informe, "LICENCIAS: lo que se ve al abrir \"Choose a CC license\"", visibles(page))
        // Se cierra como hace el Uploader (licencia por defecto + Submit)
        page.getByText("CC0 (aka CC Zero)").first().click()
        page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Submit")).click()
        page.waitForTimeout(1000.0)

        // --- 2. Categorías ---
        progreso.paso("Categorías")
        val categoria = page.getByRole(AriaRole.TEXTBOX, Page.GetByRoleOptions().setName("First level classification/"))
        val paneles = page.locator(".el-cascader-menu")
        var abierto = false
        for (intento in 1..3) {
            categoria.scrollIntoViewIfNeeded()
            categoria.click()
            page.waitForTimeout(1500.0)
            if (paneles.count() > 0 && paneles.first().isVisible) {
                abierto = true
                break
            }
        }
        seccion(informe, "CATEGORÍAS: lo que se ve al abrir \"First level classification\"", visibles(page))
        if (!abierto) {
            informe.append("(no he encontrado paneles .el-cascader-menu: mira arriba la estructura)\n")
        } else {
            val primerNivel = paneles.first().locator("li")
            val n = primerNivel.count()
            informe.append("\nPrimer nivel: $n opciones. Segundo nivel de cada una:\n")
            for (i in 0 until n) {
                try {
                    // Si se ha cerrado (por ejemplo, al elegir una opción sin segundo nivel), se vuelve a abrir
                    if (!paneles.first().isVisible) {
                        categoria.click()
                        page.waitForTimeout(1500.0)
                    }
                    val item = primerNivel.nth(i)
                    val nombre = item.innerText().trim()
                    item.click(Locator.ClickOptions().setTimeout(5_000.0))
                    page.waitForTimeout(800.0)
                    val segundo = if (paneles.count() > 1) {
                        paneles.nth(1).locator("li").allInnerTexts().map { it.trim() }
                    } else {
                        emptyList()
                    }
                    informe.append("- $nombre: ${if (segundo.isEmpty()) "(sin segundo nivel)" else segundo.joinToString(" | ")}\n")
                } catch (e: Exception) {
                    informe.append("- opción ${i + 1}: no he podido abrirla (${e.message?.lineSequence()?.firstOrNull()})\n")
                }
            }
            informe.append("\nClases del primer elemento del primer nivel: ")
                .append(primerNivel.first().getAttribute("class") ?: "?").append('\n')
            page.keyboard().press("Escape")
        }

        // --- 3. Visibilidad y tipo de impresión ---
        progreso.paso("Tipo de impresión")
        for (texto in listOf("FDM", "Public")) {
            val item = page.locator(".el-form-item").filter(Locator.FilterOptions().setHasText(texto)).first()
            val contenido = if (item.count() > 0) item.innerText() else "(no encontrado)"
            val etiquetas = if (item.count() > 0) item.locator("label").allInnerTexts().map { it.trim() } else emptyList()
            seccion(informe, "CAMPO QUE CONTIENE \"$texto\"", "$contenido\n\nlabels: ${etiquetas.joinToString(" | ")}")
        }
    }

    private fun seccion(informe: StringBuilder, titulo: String, contenido: String) {
        informe.append("\n========== $titulo ==========\n").append(contenido.trimEnd()).append('\n')
    }

    /** Los desplegables, ventanas y menús que se ven ahora: su clase, su texto y su estructura. */
    private fun visibles(page: Page): String = page.evaluate(
        """() => {
          const visible = e => e.getClientRects().length > 0 && getComputedStyle(e).visibility !== 'hidden'
              && getComputedStyle(e).display !== 'none';
          const estructura = (e, nivel) => {
            if (nivel > 4) return '';
            const yo = '  '.repeat(nivel) + e.tagName.toLowerCase() + (e.className && typeof e.className === 'string'
                ? '.' + e.className.trim().split(/\s+/).join('.') : '') + '\n';
            return yo + [...e.children].slice(0, 4).map(h => estructura(h, nivel + 1)).join('');
          };
          return [...document.querySelectorAll('.el-popper, .el-select-dropdown, .el-cascader__dropdown, [role=listbox], [role=menu], .el-dialog')]
            .filter(visible)
            .map(e => '--- ' + (typeof e.className === 'string' ? e.className : '') + '\n' + e.innerText.slice(0, 5000)
                + '\n\n(estructura)\n' + estructura(e, 0))
            .join('\n') || '(no se ve ningún desplegable)';
        }"""
    ) as String

    /** Los nombres de lo que hay en window.__NUXT__ (sin los valores, que pueden tener datos de tu cuenta). */
    private fun estructuraNuxt(page: Page): String = page.evaluate(
        """() => {
          const n = window.__NUXT__;
          if (!n) return '(no hay window.__NUXT__)';
          const claves = o => o && typeof o === 'object' ? Object.keys(o).join(', ') : typeof o;
          return 'arriba: ' + claves(n) + '\ndata[0]: ' + claves((n.data || [])[0]) + '\nstate: ' + claves(n.state);
        }"""
    ) as String

    private fun cerrarCookies(page: Page) {
        val boton = page.getByRole(AriaRole.BUTTON, Page.GetByRoleOptions().setName("Necessary Only"))
        try {
            boton.waitFor(Locator.WaitForOptions().setTimeout(3_000.0))
            boton.click()
        } catch (_: TimeoutError) {
            // no hay aviso de cookies
        }
    }
}
