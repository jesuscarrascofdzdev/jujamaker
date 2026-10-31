package dev.jefe.makeronline;

import com.microsoft.playwright.*;

import java.util.Scanner;

/**
 * PASO 1 — se ejecuta UNA sola vez (o cuando caduque la sesión).
 *
 * Abre tu Chrome real con el perfil chrome-profile/. Tú pulsas
 * "Iniciar sesión con Google" en MakerOnline, eliges tu cuenta y listo:
 * la sesión queda guardada en el perfil para el agente.
 */
public class SaveSession {
    public static void main(String[] args) throws Exception {
        Rutas rutas = Rutas.carpetaActual();
        Progreso progreso = new ProgresoConsola();
        try (AgentLock lock = AgentLock.acquire(rutas.getCandado(), "SaveSession", false, progreso);
             Playwright pw = Browsers.playwright();
             BrowserContext ctx = Browsers.openChrome(pw, rutas.getPerfilChrome(), 0)) {
            Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
            page.navigate("https://www.makeronline.com/en/");

            progreso.info("Inicia sesión con Google en la ventana de Chrome.");
            progreso.info("Cuando veas que ya estás dentro de MakerOnline, pulsa ENTER aquí...");
            new Scanner(System.in).nextLine();

            progreso.info("Sesión guardada en " + rutas.getPerfilChrome().toAbsolutePath()
                    + " (¡no la subas a git ni la compartas!).");
        } catch (AgenteException e) {
            progreso.error(e.getMessage());
            System.exit(e.getCodigoSalida());
        }
    }
}
