package dev.jefe.makeronline;

import com.microsoft.playwright.*;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Abre tu Google Chrome real (no el Chromium de Playwright) con un perfil
 * dedicado (chrome-profile/, ver Rutas).
 *
 * ¿Por qué? Google bloquea "Iniciar sesión con Google" en navegadores
 * que detecta como automatizados. Con Chrome real + perfil persistente:
 *  - haces el login con Google a mano UNA vez,
 *  - las cookies (de Google y de MakerOnline) se quedan en el perfil,
 *  - el agente reutiliza ese perfil y ya entra logueado.
 *
 * Es un perfil aparte: no toca tu Chrome de diario (ciérralo igualmente
 * si da problemas de bloqueo del perfil).
 */
public final class Browsers {

    private Browsers() {}

    /**
     * Arranca Playwright sin descargar sus propios navegadores (Chromium, Firefox y WebKit,
     * unos 400 MB): aquí siempre se usa el Google Chrome instalado (ver setChannel("chrome")).
     * Sin esto, la primera vez que alguien abre la app instalada se los bajaría sin avisar.
     */
    public static Playwright playwright() {
        Map<String, String> env = new HashMap<>();
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        return Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }

    /** Chrome con ventana normal (Uploader, SaveSession, Codegen). */
    public static BrowserContext openChrome(Playwright pw, Path perfil, double slowMoMs) {
        return openChrome(pw, perfil, slowMoMs, false, false);
    }

    /**
     * @param perfil          carpeta del perfil de Chrome con la sesión (Rutas.getPerfilChrome()).
     * @param headless        true = sin ventana (Chrome headless).
     * @param fueraDePantalla true = ventana normal pero colocada fuera de la pantalla:
     *                        funciona igual que una visible y no ocupa sitio (lo usa el
     *                        Downloader, que la trae a la vista si hace falta un captcha).
     */
    public static BrowserContext openChrome(Playwright pw, Path perfil, double slowMoMs,
                                            boolean headless, boolean fueraDePantalla) {
        List<String> args = new ArrayList<>(List.of("--disable-blink-features=AutomationControlled"));
        if (fueraDePantalla && !headless) args.add("--window-position=-10000,-10000");
        // Chrome recuerda en el perfil dónde estaba la ventana al cerrarse: si la última vez estuvo
        // fuera de la pantalla, volvería a abrirse ahí aunque ahora tenga que verse. Se coloca a la vista.
        if (!fueraDePantalla && !headless) args.add("--window-position=80,40");

        BrowserType.LaunchPersistentContextOptions opts = new BrowserType.LaunchPersistentContextOptions()
                .setChannel("chrome")          // usa Google Chrome instalado
                .setHeadless(headless)
                .setAcceptDownloads(true)
                .setSlowMo(slowMoMs)
                // Quita la marca de "navegador controlado por software"
                // que Google usa para bloquear el login.
                .setIgnoreDefaultArgs(List.of("--enable-automation"))
                .setArgs(args);
        if (headless || fueraDePantalla) {
            opts.setViewportSize(1366, 900); // tamaño de escritorio para que la web use su diseño normal
        }
        return pw.chromium().launchPersistentContext(perfil, opts);
    }
}
