package dev.jefe.makeronline

import com.fasterxml.jackson.databind.ObjectMapper
import com.microsoft.playwright.BrowserContext
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.RequestOptions
import java.nio.file.Files
import java.nio.file.Path

/**
 * La sesión de MakerOnline guardada en el perfil de Chrome (chrome-profile/).
 *
 * - [comprobar] mira si Chrome está instalado y si la sesión guardada sirve.
 * - [abrirParaIniciar] abre Chrome en MakerOnline para que la persona inicie sesión con Google.
 * - [buscarUsuario] busca en MakerOnline a alguien para añadirlo a la lista de usuarios.
 *
 * Todo lo que usa Chrome coge el candado (ver AgentLock), así que no choca con los
 * programas de terminal. Y como Playwright no admite varios hilos, hay que llamarlo
 * siempre desde el mismo hilo.
 */
class Sesion(private val rutas: Rutas) {

    /**
     * Cómo está la sesión.
     *
     * @property chromeInstalado si Google Chrome está instalado (sin él no funciona nada)
     * @property iniciada        si hay sesión de MakerOnline y MakerOnline la acepta
     * @property nombre          el nombre visible de la cuenta, si hay sesión
     * @property motivo          por qué no hay sesión, para enseñárselo a la persona
     */
    data class Estado(
        val chromeInstalado: Boolean,
        val iniciada: Boolean,
        val nombre: String? = null,
        val motivo: String? = null,
    )

    /**
     * Lo encontrado al buscar a alguien para añadirlo a la lista.
     *
     * @property usuario quién es, o null si no se ha encontrado
     * @property error   por qué no se ha encontrado, para enseñárselo a la persona (o null)
     */
    data class Busqueda(val usuario: BuscadorUsuarios.Usuario?, val error: String?)

    /**
     * Chrome abierto para iniciar sesión. Mientras está abierto tiene el candado:
     * hay que cerrarlo ([close]) cuando la persona diga que ya ha entrado.
     */
    class Abierta internal constructor(
        private val candado: AgentLock,
        private val playwright: Playwright,
        private val contexto: BrowserContext,
    ) : AutoCloseable {
        override fun close() {
            // Cada cosa por separado: si la persona ya ha cerrado Chrome, lo demás se cierra igual
            try { contexto.close() } catch (_: Exception) { }
            try { playwright.close() } catch (_: Exception) { }
            try { candado.close() } catch (_: Exception) { }
        }
    }

    /**
     * Comprueba Chrome y la sesión. Abre Chrome sin ventana unos segundos para leer la sesión
     * del perfil y preguntar a MakerOnline si sigue valiendo.
     *
     * @throws AgenteException si otro programa está usando Chrome (candado ocupado)
     */
    fun comprobar(progreso: Progreso): Estado {
        if (!chromeInstalado()) return Estado(chromeInstalado = false, iniciada = false, motivo = "Google Chrome no está instalado")
        if (!Files.isDirectory(rutas.perfilChrome)) {
            return Estado(chromeInstalado = true, iniciada = false, motivo = "todavía no has iniciado sesión")
        }
        AgentLock.acquire(rutas.candado, "JujaMaker (comprobando la sesión)", false, progreso).use {
            Browsers.playwright().use { pw ->
                Browsers.openChrome(pw, rutas.perfilChrome, 0.0, true, false).use { ctx ->
                    val token = token(ctx)
                        ?: return Estado(chromeInstalado = true, iniciada = false, motivo = "no hay sesión iniciada")
                    // Mismo endpoint que usa la web al cargar tu perfil (comprobado el 31/10/2026)
                    val r = ctx.request().get(
                        "$WEB/api/user/personal/info",
                        RequestOptions.create()
                            .setHeader("Authorization", token)
                            .setHeader("Accept", "application/json"),
                    )
                    return interpretar(r.status(), r.text())
                }
            }
        }
    }

    /**
     * Abre Chrome con ventana en MakerOnline para que la persona inicie sesión con Google.
     * Devuelve la ventana abierta: hay que cerrarla cuando diga que ya ha entrado y luego
     * llamar a [comprobar].
     *
     * @throws AgenteException si otro programa está usando Chrome (candado ocupado)
     */
    fun abrirParaIniciar(progreso: Progreso): Abierta {
        val candado = AgentLock.acquire(rutas.candado, "JujaMaker (iniciando sesión)", false, progreso)
        var pw: Playwright? = null
        try {
            pw = Browsers.playwright()
            val ctx = Browsers.openChrome(pw, rutas.perfilChrome, 0.0)
            val page = if (ctx.pages().isEmpty()) ctx.newPage() else ctx.pages()[0]
            page.navigate("$WEB/en/")
            page.bringToFront()
            return Abierta(candado, pw, ctx)
        } catch (e: Exception) {
            try { pw?.close() } catch (_: Exception) { }
            try { candado.close() } catch (_: Exception) { }
            throw e
        }
    }

    /**
     * Busca en MakerOnline a la persona que se quiere añadir a usuarios.json, igual que el
     * Downloader al repasar la lista: por la URL de su perfil (lo más fiable), su @ o su nombre.
     * Abre Chrome sin ventana unos segundos.
     *
     * @throws AgenteException si no hay sesión o si otro programa está usando Chrome
     */
    fun buscarUsuario(entrada: String, progreso: Progreso): Busqueda {
        if (!Files.isDirectory(rutas.perfilChrome)) throw AgenteException(SIN_SESION)
        AgentLock.acquire(rutas.candado, "JujaMaker (buscando un usuario)", false, progreso).use {
            Browsers.playwright().use { pw ->
                Browsers.openChrome(pw, rutas.perfilChrome, 0.0, true, false).use { ctx ->
                    ctx.setDefaultTimeout(30_000.0)
                    val token = token(ctx) ?: throw AgenteException(SIN_SESION)
                    val page = if (ctx.pages().isEmpty()) ctx.newPage() else ctx.pages()[0]
                    // El buscador cuenta con progreso.error por qué no lo encuentra: aquí se apunta
                    val errores = ArrayList<String>()
                    val apuntando = object : Progreso by progreso {
                        override fun error(texto: String) {
                            errores += texto
                        }
                    }
                    val u = BuscadorUsuarios(page, Api(ctx.request(), token), apuntando).buscar(entrada.trim())
                    val error = if (u != null) null else errores.joinToString("\n").ifBlank { "No lo encuentro en MakerOnline." }
                    return Busqueda(u, error)
                }
            }
        }
    }

    companion object {
        private const val WEB = Api.WEB
        /** La cookie de la sesión de MakerOnline (ver el documento de contexto). */
        private const val COOKIE = "mo_access_token"
        private val JSON = ObjectMapper()
        private const val SIN_SESION = "No hay sesión iniciada en MakerOnline. Iníciala en la pantalla Sesión."

        /** El valor de la cookie de la sesión, o null si no hay. */
        private fun token(ctx: BrowserContext): String? =
            ctx.cookies(WEB).firstOrNull { it.name == COOKIE && !it.value.isNullOrBlank() }?.value

        /** Lee la respuesta de api/user/personal/info: code 0 = sesión válida. */
        @JvmStatic
        fun interpretar(status: Int, cuerpo: String): Estado {
            if (status in 500..599) {
                return Estado(chromeInstalado = true, iniciada = false,
                    motivo = "MakerOnline no responde bien ahora mismo (HTTP $status); prueba más tarde")
            }
            val j = try { JSON.readTree(cuerpo) } catch (_: Exception) { null }
            return if (status == 200 && j != null && j.path("code").asInt(-1) == 0) {
                val nombre = j.path("data").path("display_name").asText("").ifBlank { null }
                Estado(chromeInstalado = true, iniciada = true, nombre = nombre)
            } else {
                Estado(chromeInstalado = true, iniciada = false, motivo = "la sesión ha caducado; vuelve a iniciarla")
            }
        }

        /**
         * Si Google Chrome está instalado, mirando donde lo instala Google en cada sistema
         * (que es donde lo busca Playwright con el canal "chrome").
         */
        @JvmStatic
        fun chromeInstalado(): Boolean {
            val os = System.getProperty("os.name").lowercase()
            val candidatos = when {
                os.contains("win") -> listOfNotNull(
                    System.getenv("ProgramFiles"),
                    System.getenv("ProgramFiles(x86)"),
                    System.getenv("LOCALAPPDATA"),
                ).map { Path.of(it, "Google", "Chrome", "Application", "chrome.exe") }
                os.contains("mac") -> listOf(Path.of("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"))
                else -> listOf(Path.of("/opt/google/chrome/chrome"), Path.of("/usr/bin/google-chrome"),
                    Path.of("/usr/bin/google-chrome-stable"))
            }
            return candidatos.any { Files.isRegularFile(it) }
        }
    }
}
