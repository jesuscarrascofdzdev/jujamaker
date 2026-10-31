package dev.jefe.makeronline;

import com.microsoft.playwright.*;

/**
 * Abre Chrome con tu sesión y el Playwright Inspector para grabar clics
 * (botón Record). Útil si MakerOnline cambia su formulario.
 * Copia el código del Inspector ANTES de pulsar Resume.
 */
public class Codegen {
    public static void main(String[] args) throws Exception {
        Rutas rutas = Rutas.carpetaActual();
        Progreso progreso = new ProgresoConsola();
        try (AgentLock lock = AgentLock.acquire(rutas.getCandado(), "Codegen", false, progreso);
             Playwright pw = Browsers.playwright();
             BrowserContext ctx = Browsers.openChrome(pw, rutas.getPerfilChrome(), 0)) {
            Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
            page.navigate("https://www.makeronline.com/en/upload");
            page.pause();
        } catch (AgenteException e) {
            progreso.error(e.getMessage());
            System.exit(e.getCodigoSalida());
        }
    }
}
