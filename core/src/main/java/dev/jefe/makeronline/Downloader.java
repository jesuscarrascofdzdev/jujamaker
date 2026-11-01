package dev.jefe.makeronline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.gson.JsonObject;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.Cookie;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Repasa los usuarios de usuarios.json y descarga sus modelos a los que todavía
 * no has dado "me gusta", dándoles like al terminar cada descarga. Así el like
 * hace de marca de "ya descargado" y la próxima vez se saltan.
 *
 * Para ir rápido, de cada usuario solo mira sus 10 últimos modelos subidos.
 *
 * Trabaja en segundo plano (Chrome headless): no se abre ninguna ventana.
 * Usa la misma sesión que el Uploader (chrome-profile/), y no puede estar en
 * marcha a la vez que él (ver AgentLock).
 *
 * Uso:  Downloader [usuario] [opciones]
 *   sin usuario    repasa todos los de usuarios.json
 *   usuario        solo ese: "@usuario_1234567 / NombreVisible", el @, el nombre, la URL de su perfil o su id
 *
 * Opciones:
 *   --ultimos=N    cuántos de los últimos modelos de cada usuario se miran (por defecto 10)
 *   --todos        mira todos los modelos de cada usuario (más lento; útil la primera vez)
 *   --simular      solo lista lo que descargaría; no descarga ni da like
 *   --max=N        descarga como mucho N modelos en esta ejecución (sumando todos los usuarios)
 *   --espera=S     pausa entre descargas: al azar entre S y S+20 s (por defecto 10, o sea 10-30 s),
 *                  contada desde que empieza la descarga anterior; siempre igual, aunque salgan captchas
 *   --perfil       descarga también el perfil de impresión (.3mf), no solo los archivos del modelo
 *   --visible      ventana de Chrome a la vista todo el rato (para ver qué hace)
 *   --sin-ventana  Chrome sin ventana (headless); si sale un captcha, se para
 *   --esperar      si el Uploader está en marcha, espera a que termine en vez de salir
 *
 * Para pararlo sin dejar nada a medias: escribir "parar" y ENTER en la consola. Termina el
 * modelo que esté bajando (descarga + like) y no empieza otro; las pausas y los captchas
 * pendientes se cortan en el momento.
 *
 * Ventana: por defecto Chrome se abre FUERA DE LA PANTALLA (no ocupa sitio, solo se ve en
 * la barra de tareas) y solo aparece delante si MakerOnline pide un captcha, para que lo
 * resuelvas tú. No se minimiza porque Chrome minimizado deja de pintar y Playwright se cuelga.
 *
 * Resultado: modelos-descargados/<usuario>/<título>_<id>/ con los archivos, portada e info.json,
 * y descargados.txt (en la raíz del proyecto) con lo ya bajado.
 */
public class Downloader {

    /** Se muestra al arrancar: así se sabe qué versión del código se está ejecutando. */
    private static final String VERSION = "12 (usuarios sin marcar en la app)";

    private static final String WEB = Api.WEB;
    private static final int ULTIMOS_POR_DEFECTO = 10;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** Código con el que MakerOnline pide captcha. No se intenta saltar: se para. */
    private static final int CODIGO_CAPTCHA = 1003;

    /**
     * usuario == null → todos los de usuarios.json; ultimos == 0 → todos sus modelos.
     *
     * @param fijos  usuarios que se repasan siempre los primeros, antes de los de usuarios.json
     *               (la app pone aquí al creador). Solo cuentan si usuario == null. En la terminal, ninguno.
     * @param saltar entradas de usuarios.json que no se repasan esta vez (las que se han desmarcado
     *               en la app). Solo cuentan si usuario == null. En la terminal, ninguna.
     */
    public record Opciones(String usuario, int ultimos, boolean simular, int max, int espera,
                           boolean perfil, Modo modo, boolean esperar, List<String> fijos, List<String> saltar) {}

    /** Cómo se ve Chrome mientras trabaja. */
    public enum Modo {
        /** Por defecto: ventana fuera de la pantalla; aparece si hay captcha. */
        APARTADA,
        /** --visible: ventana normal todo el rato. */
        VISIBLE,
        /** --sin-ventana: headless; un captcha para la ejecución. */
        SIN_VENTANA
    }

    /**
     * Cómo ha ido una ejecución.
     *
     * @param descargados        modelos descargados (y con like)
     * @param soloLike           ya estaban descargados y solo faltaba el like
     * @param yaTenianLike       se han saltado porque ya tenían like
     * @param errores            usuarios o modelos que han fallado
     * @param carpetasConErrores carpetas de usuario con errores.txt y capturas error_<id>.png
     * @param simulado           true con --simular (no se ha descargado nada)
     * @param parado             true si se ha parado a petición de la persona
     */
    public record Resumen(int descargados, int soloLike, int yaTenianLike, int errores,
                          List<Path> carpetasConErrores, boolean simulado, boolean parado) {}

    /** Dónde están la sesión de Chrome, usuarios.json, descargados.txt y las descargas. */
    private final Rutas rutas;
    /** A quién se le cuenta lo que va pasando (terminal o app). */
    private final Progreso progreso;
    /** Si se pide parar, se para en el siguiente punto seguro. */
    private final Cancelacion cancelacion;
    /** Ids de los modelos apuntados en descargados.txt. */
    private final Set<Long> registrados = new HashSet<>();
    /** La ventana de Chrome de esta ejecución (ver Ventana). */
    private Ventana ventana;

    public Downloader(Rutas rutas, Progreso progreso, Cancelacion cancelacion) {
        this.rutas = rutas;
        this.progreso = progreso;
        this.cancelacion = cancelacion;
    }

    /** Sin opción de parar. */
    public Downloader(Rutas rutas, Progreso progreso) {
        this(rutas, progreso, new Cancelacion());
    }

    /** Pausa entre descargas: entre S y S + MARGEN_ESPERA segundos al azar (--espera=S). */
    private static final int ESPERA_POR_DEFECTO = 10;
    private static final int MARGEN_ESPERA = 20;

    /** Contadores y ritmo de toda la ejecución (todos los usuarios). */
    private static final class Estado {
        int descargados, soloLike, saltados, errores, restantes;
        boolean pausar, parar;
        /** Se ha parado a petición de la persona. */
        boolean cancelado;
        /** Cuándo empezó el último modelo que generó tráfico: la pausa se cuenta desde ahí. */
        long inicioUltimo;
        final Set<Path> conErrores = new LinkedHashSet<>();

        Estado(int max) { this.restantes = max; }
    }

    record Modelo(long id, String titulo, boolean liked, boolean premium) {}

    enum Resultado { DESCARGADO, SOLO_LIKE, YA_TENIA_LIKE }

    /** MakerOnline ha pedido captcha y no se ha podido resolver: se para la ejecución. */
    static class CaptchaException extends RuntimeException {
        CaptchaException(String m) { super(m); }
    }

    /**
     * La ventana de Chrome. En modo APARTADA está fuera de la pantalla: Chrome funciona
     * con normalidad (Playwright lo arranca con --disable-backgrounding-occluded-windows),
     * pero no ocupa sitio; solo se ve en la barra de tareas. Si MakerOnline pide captcha,
     * se trae a la vista con las herramientas de Chrome (CDP) y luego se vuelve a apartar.
     * La usa también el Uploader (sin "Ver Chrome mientras sube"): la trae a la vista para
     * que revises cada borrador.
     */
    static final class Ventana {
        static final long TIEMPO_CAPTCHA_MS = 5 * 60_000;
        /** Posición fuera de cualquier monitor. */
        static final int FUERA = -10_000;

        private final Modo modo;
        private final Page page;
        private final Progreso progreso;
        private CDPSession cdp;
        private int windowId = -1;

        Ventana(Modo modo, BrowserContext ctx, Page page, Progreso progreso) {
            this.modo = modo;
            this.page = page;
            this.progreso = progreso;
            if (modo != Modo.APARTADA) return;
            try {
                cdp = ctx.newCDPSession(page);
                windowId = cdp.send("Browser.getWindowForTarget").get("windowId").getAsInt();
                apartar(); // por si Chrome no hizo caso a --window-position
            } catch (Exception e) {
                cdp = null;
                progreso.nota("no he podido apartar la ventana de Chrome: " + primeraLinea(e));
            }
        }

        /** Saca la ventana para que resuelvas el captcha; sin ventana, para la ejecución. */
        void pedirCaptcha(Page p, String para) {
            if (modo == Modo.SIN_VENTANA) throw new CaptchaException("MakerOnline pide captcha para " + para);
            mostrar();
            progreso.captcha(para, TIEMPO_CAPTCHA_MS / 60_000);
        }

        /** Captcha resuelto (o tiempo agotado): la ventana vuelve fuera de la pantalla. */
        void finCaptcha() {
            apartar();
            progreso.captchaTerminado();
        }

        void mostrar() {
            if (cdp != null) mover(80, 40);
            page.bringToFront();
        }

        void apartar() {
            if (cdp != null) mover(FUERA, FUERA);
        }

        private void mover(int x, int y) {
            try {
                JsonObject normal = new JsonObject();
                normal.addProperty("windowState", "normal"); // por si alguien la minimizó
                cdp.send("Browser.setWindowBounds", argumentos(normal));
                JsonObject posicion = new JsonObject();
                posicion.addProperty("left", x);
                posicion.addProperty("top", y);
                cdp.send("Browser.setWindowBounds", argumentos(posicion));
            } catch (Exception e) {
                progreso.nota("no he podido mover la ventana de Chrome: " + primeraLinea(e));
            }
        }

        private JsonObject argumentos(JsonObject bounds) {
            JsonObject a = new JsonObject();
            a.addProperty("windowId", windowId);
            a.add("bounds", bounds);
            return a;
        }
    }

    public static void main(String[] args) throws Exception {
        Opciones op = leerArgs(args);
        if (op == null) {
            ayuda();
            System.exit(1);
        }
        Rutas rutas = Rutas.carpetaActual();
        Progreso progreso = new ProgresoConsola();
        Cancelacion cancelacion = new Cancelacion();
        // Solo un programa del agente a la vez: comparten chrome-profile/
        try (AgentLock lock = AgentLock.acquire(rutas.getCandado(), "Downloader", op.esperar(), progreso)) {
            EntradaConsola.iniciar(cancelacion, progreso);
            new Downloader(rutas, progreso, cancelacion).ejecutar(op);
        } catch (AgenteException e) {
            progreso.error(e.getMessage());
            System.exit(e.getCodigoSalida());
        }
    }

    /**
     * Repasa los usuarios y descarga sus modelos nuevos con estas opciones.
     *
     * @throws AgenteException si no hay sesión, ha caducado o la web no carga
     */
    public Resumen ejecutar(Opciones op) throws Exception {
        progreso.info("Downloader · versión " + VERSION);
        // Usuario por argumento → solo ese; si no, los fijos y luego todos los de usuarios.json
        List<String> entradas;
        if (op.usuario() != null) {
            entradas = List.of(op.usuario());
        } else {
            List<String> lista = leerLista(!op.fijos().isEmpty());
            List<String> marcados = lista.stream().filter(e -> !op.saltar().contains(e)).toList();
            if (marcados.size() < lista.size()) {
                progreso.info("Sin marcar (no se repasan esta vez): " + (lista.size() - marcados.size()));
            }
            entradas = ListaUsuarios.conFijos(op.fijos(), marcados);
        }
        if (entradas.isEmpty()) return new Resumen(0, 0, 0, 0, List.of(), op.simular(), false);
        if (!Files.isDirectory(rutas.getPerfilChrome())) {
            throw new AgenteException("No hay sesión guardada. Ejecuta primero SaveSession.");
        }
        long inicio = System.currentTimeMillis();
        Estado est = new Estado(op.max());
        leerRegistro();

        try (Playwright pw = Browsers.playwright();
             BrowserContext ctx = Browsers.openChrome(pw, rutas.getPerfilChrome(), op.modo() == Modo.VISIBLE ? 100 : 0,
                     op.modo() == Modo.SIN_VENTANA, op.modo() == Modo.APARTADA)) {
            ctx.setDefaultTimeout(30_000);
            Page page = ctx.pages().isEmpty() ? ctx.newPage() : ctx.pages().get(0);
            ventana = new Ventana(op.modo(), ctx, page, progreso);

            // --- Sesión ---
            progreso.paso("Comprobando la sesión" + switch (op.modo()) {
                case APARTADA -> " (Chrome fuera de la pantalla; aparecerá solo si hay captcha)";
                case SIN_VENTANA -> " (sin ventana)";
                case VISIBLE -> "";
            });
            page.navigate(WEB + "/en/");
            if (!Boolean.TRUE.equals(page.evaluate("() => !!window.__NUXT__"))) {
                throw new AgenteException("La web de MakerOnline no ha cargado bien.");
            }
            cerrarCookies(page, 3_000);
            String token = token(ctx);
            if (token == null) {
                throw new AgenteException("No hay sesión iniciada en MakerOnline (o ha caducado). Ejecuta SaveSession.");
            }
            Api api = new Api(ctx.request(), token);

            // --- Repaso de todos los usuarios ---
            try {
                for (int k = 0; k < entradas.size() && !est.parar; k++) {
                    cancelacion.comprobar();
                    String entrada = entradas.get(k);
                    progreso.usuario(k + 1, entradas.size(), entrada);
                    try {
                        procesarUsuario(page, ctx, api, entrada, op, est);
                    } catch (CanceladoException e) {
                        throw e;
                    } catch (Exception e) {
                        est.errores++;
                        progreso.error("No se ha podido revisar " + entrada + ": " + primeraLinea(e));
                    }
                }
            } catch (CanceladoException e) {
                // No es un error: lo que estaba a medias no ha llegado a empezar (ver los puntos seguros)
                est.cancelado = true;
            }

            long min = Duration.ofMillis(System.currentTimeMillis() - inicio).toMinutes();
            progreso.info(String.format("%n======== %s en %d min ========",
                    est.cancelado ? "Parado (lo has pedido tú)" : "Terminado", min));
            if (op.simular()) {
                progreso.info("(--simular: no se ha descargado nada ni se ha dado ningún like)");
            } else {
                progreso.info(String.format("%d descargados, %d solo faltaba el like, %d ya tenían like, %d con error.",
                        est.descargados, est.soloLike, est.saltados, est.errores));
                progreso.info("Carpeta: " + rutas.getDescargas().toAbsolutePath());
                for (Path p : est.conErrores) {
                    progreso.info("Detalle de errores: " + p.resolve("errores.txt").toAbsolutePath());
                }
            }
        }
        return new Resumen(est.descargados, est.soloLike, est.saltados, est.errores,
                List.copyOf(est.conErrores), op.simular(), est.cancelado);
    }

    // ================================================================ un usuario

    private void procesarUsuario(Page page, BrowserContext ctx, Api api, String entrada,
                                 Opciones op, Estado est) throws Exception {
        BuscadorUsuarios.Usuario u = new BuscadorUsuarios(page, api, progreso).buscar(entrada);
        if (u == null) {
            est.errores++;
            return;
        }
        progreso.info("Usuario: " + u.nombre() + "  " + u.handle()
                + "\n         " + u.url());

        List<Modelo> revisados = listarModelos(api, u.id(), op.ultimos());
        List<Modelo> pendientes = revisados.stream().filter(m -> !m.liked()).toList();
        progreso.info(String.format("%s %d modelos: %d ya con like, %d por descargar.",
                op.ultimos() > 0 ? "Últimos" : "Todos sus", revisados.size(),
                revisados.size() - pendientes.size(), pendientes.size()));

        if (op.simular()) {
            for (Modelo m : pendientes) {
                progreso.info("  · " + m.titulo() + (m.premium() ? "  [premium]" : "") + "\n    " + urlModelo(m));
            }
            return;
        }
        if (pendientes.isEmpty()) return;

        // modelos-descargados/<usuario>/
        Path carpetaUsuario = rutas.getDescargas().resolve(limpio(u.nombre(), 60));
        Files.createDirectories(carpetaUsuario);

        int antes = est.descargados;
        for (int i = 0; i < pendientes.size(); i++) {
            cancelacion.comprobar(); // punto seguro: entre un modelo y el siguiente
            if (est.restantes <= 0) {
                progreso.info("Alcanzado el máximo de descargas (--max): paro aquí.");
                est.parar = true;
                break;
            }
            if (est.pausar) {
                pausa(page, op.espera(), est);
                est.pausar = false;
            }
            Modelo m = pendientes.get(i);
            progreso.modelo(i + 1, pendientes.size(), m.titulo());
            long inicio = System.currentTimeMillis();
            try {
                switch (procesar(page, ctx, m, carpetaUsuario, op)) {
                    case DESCARGADO -> { est.descargados++; est.restantes--; est.pausar = true; est.inicioUltimo = inicio; }
                    case SOLO_LIKE -> { est.soloLike++; est.pausar = true; est.inicioUltimo = inicio; }
                    case YA_TENIA_LIKE -> est.saltados++;
                }
            } catch (CanceladoException e) {
                throw e;
            } catch (CaptchaException e) {
                est.errores++;
                est.parar = true;
                est.conErrores.add(carpetaUsuario);
                progreso.error(e.getMessage()
                        + "\n  Paro aquí (también con el resto de usuarios) para no forzar nada."
                        + "\n  Vuelve a intentarlo más tarde" + (op.modo() == Modo.SIN_VENTANA
                        ? ", o lánzalo sin --sin-ventana para que aparezca Chrome y lo resuelvas tú."
                        : " y resuelve el captcha cuando aparezca la ventana de Chrome."));
                registrarError(carpetaUsuario, m, e.getMessage());
                break;
            } catch (Exception e) {
                est.errores++;
                est.pausar = true; // ha habido tráfico: se respeta la espera igualmente
                est.inicioUltimo = inicio;
                est.conErrores.add(carpetaUsuario);
                String msg = primeraLinea(e);
                progreso.error("Error: " + msg);
                registrarError(carpetaUsuario, m, msg);
                captura(page, carpetaUsuario.resolve("error_" + m.id() + ".png"));
            }
        }
        progreso.paso(u.nombre() + ": " + (est.descargados - antes) + " descargados en " + carpetaUsuario);
    }

    /**
     * Lee usuarios.json (o lo crea vacío la primera vez, con las instrucciones en comentarios).
     *
     * @param hayFijos si hay usuarios fijos: entonces una lista vacía no impide seguir
     */
    private List<String> leerLista(boolean hayFijos) throws IOException {
        Path lista = rutas.getUsuarios();
        if (!Files.exists(lista)) {
            ListaUsuarios.guardar(lista, List.of());
            progreso.info("He creado " + lista.toAbsolutePath());
            if (!hayFijos) progreso.info("Añade ahí los usuarios que quieres repasar y vuelve a ejecutar.");
            return List.of();
        }
        // Mismas reglas que en la app (ver ListaUsuarios)
        ListaUsuarios.Lectura lectura = ListaUsuarios.leer(lista);
        if (lectura.getError() != null) {
            progreso.error(lectura.getError());
            return List.of();
        }
        List<String> out = lectura.getEntradas();
        if (!out.isEmpty()) progreso.info("Usuarios en la lista: " + out.size());
        else if (!hayFijos) progreso.info("usuarios.json está vacío: añade algún usuario y vuelve a ejecutar.");
        return out;
    }

    private static String primeraLinea(Exception e) {
        return e.getMessage() == null ? e.toString() : e.getMessage().lines().findFirst().orElse(e.toString());
    }

    // ================================================================ un modelo

    private Resultado procesar(Page page, BrowserContext ctx, Modelo m, Path carpetaUsuario, Opciones op)
            throws IOException {
        Path dir = carpetaUsuario.resolve(limpio(m.titulo(), 60) + "_" + m.id());

        progreso.paso("Abriendo " + urlModelo(m));
        page.navigate(urlModelo(m));
        cerrarCookies(page, 0);
        JsonNode det = detalle(page);
        if (det == null) {
            throw new IllegalStateException("No se ha podido leer la página del modelo (¿borrado, privado o la web no ha cargado?)");
        }
        // Doble comprobación: la lista dice que no tiene like, pero lo confirmamos en su página
        if (det.path("is_like").asInt(0) == 1) {
            progreso.saltado("Ya tenía like, lo salto");
            return Resultado.YA_TENIA_LIKE;
        }

        // Ya descargado en una ejecución anterior si está en descargados.txt o si existe
        // su info.json (se escribe lo último, así que la descarga se completó)
        String usuario = carpetaUsuario.getFileName().toString();
        boolean enRegistro = registrados.contains(m.id());
        boolean yaDescargado = enRegistro || Files.exists(dir.resolve("info.json"));
        if (yaDescargado) {
            progreso.saltado("Ya estaba descargado" + (enRegistro ? " (descargados.txt)" : " en " + dir)
                    + ", solo falta el like");
            registrar(m, usuario); // los bajados antes de existir el registro se anotan ahora
        } else {
            Files.createDirectories(dir);
            int raw = det.path("files").size();
            int perfiles = det.path("print_profiles").size();
            if (raw == 0 && perfiles == 0) throw new IllegalStateException("El modelo no tiene archivos para descargar");

            if (raw > 0) descargarArchivosModelo(page, dir);
            if (perfiles > 0 && (op.perfil() || raw == 0)) descargarPerfil(page, dir);

            guardarPortada(ctx, det, dir);
            guardarInfo(page, det, m, dir);
            registrar(m, usuario);
            progreso.correcto("Descargado en " + dir);
        }

        darLike(page, m);
        return yaDescargado ? Resultado.SOLO_LIKE : Resultado.DESCARGADO;
    }

    /**
     * Pestaña "Raw Model Files". Su botón cambia según cuántos archivos tenga el modelo:
     *  - uno solo  → botón "Download" normal
     *  - varios    → botón partido "Download ▾" y, en el desplegable, "Download All"
     * (El botón de esta pestaña es .print-download-button.tailwind; el de la pestaña
     * de perfiles es .print-download-button a secas.)
     */
    private void descargarArchivosModelo(Page page, Path dir) {
        progreso.paso("Archivos del modelo");
        Locator pestana = page.getByText(Pattern.compile("^\\s*Raw Model Files")).first();
        if (pestana.count() > 0) pestana.click();

        Locator zona = page.locator(".print-download-button.tailwind")
                .filter(new Locator.FilterOptions().setVisible(true)).first();
        try {
            zona.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        } catch (TimeoutError e) {
            throw new IllegalStateException("No encuentro el botón de descarga de los archivos del modelo");
        }

        Locator desplegable = zona.locator("#download-dropdown");
        if (desplegable.count() == 0) {
            // un solo archivo: botón "Download" sin desplegable
            Locator boton = zona.getByRole(AriaRole.BUTTON,
                    new Locator.GetByRoleOptions().setName("Download").setExact(true)).first();
            esperarDescarga(page, dir, boton::click);
            return;
        }

        // varios archivos: flecha del desplegable → "Download All"
        Locator flecha = desplegable.locator(".el-dropdown__caret-button");
        Locator todo = page.locator(".el-dropdown-menu__item")
                .filter(new Locator.FilterOptions().setHasText("Download All").setVisible(true)).first();

        // el menú se abre al pasar el ratón por la flecha; si no, con un clic
        for (int intento = 1; !todo.isVisible(); intento++) {
            if (intento > 3) throw new IllegalStateException("No se abre el menú 'Download All'");
            flecha.hover();
            page.waitForTimeout(600);
            if (!todo.isVisible()) {
                flecha.click();
                page.waitForTimeout(600);
            }
        }
        esperarDescarga(page, dir, todo::click);
    }

    /**
     * Pestaña "Print Profile Files" → Download (perfil seleccionado, el primero).
     * Según el perfil, la descarga empieza directamente o sale la ventana
     * "Download Print Profile Files" con su botón "Download All".
     */
    private void descargarPerfil(Page page, Path dir) {
        progreso.paso("Perfil de impresión");
        Locator pestana = page.getByText(Pattern.compile("^\\s*Print Profile Files")).first();
        if (pestana.count() > 0) pestana.click();

        Locator boton = page.locator(".print-download-button:not(.tailwind)")
                .filter(new Locator.FilterOptions().setVisible(true))
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Download").setExact(true))
                .first();
        try {
            boton.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        } catch (TimeoutError e) {
            throw new IllegalStateException("No encuentro el botón de descarga del perfil de impresión");
        }

        Locator todo = page.locator(".el-dialog")
                .filter(new Locator.FilterOptions().setHasText("Download Print Profile Files").setVisible(true))
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Download All"));
        esperarDescarga(page, dir, () -> {
            boton.click();
            // si sale la ventana con "Download All", se pulsa; si no, la descarga ya ha empezado
            try {
                todo.waitFor(new Locator.WaitForOptions().setTimeout(5_000));
                todo.click();
            } catch (TimeoutError e) {
                // sin ventana intermedia
            }
        });
        page.keyboard().press("Escape"); // cierra la ventana del perfil si quedó abierta
    }

    /**
     * Hace el clic y espera a que el navegador empiece la descarga. Vigila a la vez
     * la respuesta de la API por si MakerOnline pide captcha, da error o pide login.
     */
    private void esperarDescarga(Page page, Path dir, Runnable clic) {
        AtomicReference<Download> descarga = new AtomicReference<>();
        AtomicReference<JsonNode> fallo = new AtomicReference<>();
        Consumer<Download> alDescargar = descarga::set;
        Consumer<Response> alResponder = r -> {
            String url = r.url();
            if (!url.contains("/api/file/download") && !url.contains("/api/file/check-is-package")) return;
            try {
                JsonNode j = JSON.readTree(r.text());
                if (j.path("code").asInt(0) != 0) fallo.set(j);
            } catch (Exception ignored) {
                // respuesta sin cuerpo JSON: no nos dice nada
            }
        };

        // por si la web abre la descarga en otra pestaña
        BrowserContext ctx = page.context();
        List<Page> pestanasNuevas = new ArrayList<>();
        Consumer<Page> alAbrirPestana = p -> {
            pestanasNuevas.add(p);
            p.onDownload(alDescargar);
        };
        Locator avisoError = page.locator(".el-message--error, .el-message--warning")
                .filter(new Locator.FilterOptions().setVisible(true)).first();

        page.onDownload(alDescargar);
        page.onResponse(alResponder);
        ctx.onPage(alAbrirPestana);
        boolean captcha = false;
        try {
            clic.run();
            long limite = System.currentTimeMillis() + 120_000;
            while (descarga.get() == null) {
                // Punto seguro: la descarga aún no ha empezado (o se espera un captcha)
                cancelacion.comprobar();
                JsonNode j = fallo.getAndSet(null);
                if (j != null) {
                    int code = j.path("code").asInt();
                    if (code == CODIGO_CAPTCHA) {
                        if (!captcha) {
                            ventana.pedirCaptcha(page, "descargar"); // lanza CaptchaException si no hay ventana
                            limite = System.currentTimeMillis() + Ventana.TIEMPO_CAPTCHA_MS;
                            captcha = true;
                        }
                    } else if (code != 1) { // con code 1 la propia web reintenta sola
                        throw new IllegalStateException("MakerOnline no deja descargar: " + j.path("message").asText("?"));
                    }
                }
                if (page.locator(".login-container .el-dialog").isVisible()) {
                    throw new IllegalStateException("MakerOnline pide iniciar sesión: ha caducado. Ejecuta SaveSession.");
                }
                if (avisoError.isVisible()) {
                    throw new IllegalStateException("MakerOnline dice: " + avisoError.innerText().trim());
                }
                if (System.currentTimeMillis() > limite) {
                    if (captcha) throw new CaptchaException("El captcha no se ha resuelto a tiempo");
                    throw new IllegalStateException("La descarga no ha empezado (tiempo agotado)");
                }
                page.waitForTimeout(500);
            }
        } finally {
            page.offDownload(alDescargar);
            page.offResponse(alResponder);
            ctx.offPage(alAbrirPestana);
            if (captcha) ventana.finCaptcha(); // resuelto (o no): la ventana vuelve fuera de la pantalla
        }

        Download d = descarga.get();
        Path destino = dir.resolve(limpioArchivo(d.suggestedFilename()));
        progreso.paso("Guardando " + destino.getFileName());
        try {
            d.saveAs(destino); // espera a que termine de bajar
            String error = d.failure();
            if (error != null) throw new IllegalStateException("La descarga ha fallado: " + error);
        } finally {
            for (Page p : pestanasNuevas) {
                if (!p.isClosed()) p.close();
            }
        }
    }

    /** Pulsa el corazón del modelo y confirma con la respuesta de la API. */
    private void darLike(Page page, Modelo m) {
        if (!page.url().contains("/" + m.id() + ".html")) {
            page.navigate(urlModelo(m));
            cerrarCookies(page, 0);
        }
        page.keyboard().press("Escape"); // por si quedó abierta alguna ventana de la descarga encima
        // Botón 👍 de la barra bajo los archivos: <div class="... like-feedback ..."><svg><use href="#icon-ic_like">
        Locator corazon = page.locator(".like-feedback svg")
                .filter(new Locator.FilterOptions().setVisible(true)).first();
        try {
            corazon.waitFor(new Locator.WaitForOptions().setTimeout(10_000));
        } catch (TimeoutError e) {
            throw new IllegalStateException("Descargado, pero no encuentro el botón de me gusta");
        }
        if (icono(corazon).contains("ic_like_click")) {
            progreso.like("Ya tenía like");
            return;
        }

        progreso.paso("Dando like");
        Response r;
        try {
            r = page.waitForResponse(resp -> resp.url().contains("/api/user/like/like"),
                    new Page.WaitForResponseOptions().setTimeout(15_000),
                    () -> corazon.click(new Locator.ClickOptions().setTimeout(10_000)));
        } catch (TimeoutError e) {
            throw new IllegalStateException("Descargado, pero el like no ha llegado a enviarse");
        }
        int code;
        String mensaje;
        try {
            JsonNode j = JSON.readTree(r.text());
            code = j.path("code").asInt(-1);
            mensaje = j.path("message").asText("?");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Descargado, pero la respuesta del like no se entiende");
        }

        if (code == CODIGO_CAPTCHA) {
            ventana.pedirCaptcha(page, "dar like"); // lanza CaptchaException si no hay ventana
            try {
                long limite = System.currentTimeMillis() + Ventana.TIEMPO_CAPTCHA_MS;
                while (!icono(corazon).contains("ic_like_click")) {
                    // Punto seguro: ya está descargado y apuntado; la próxima vez solo faltará el like
                    cancelacion.comprobar();
                    if (System.currentTimeMillis() > limite) {
                        throw new CaptchaException("Modelo descargado, pero el captcha del like no se ha resuelto a tiempo");
                    }
                    page.waitForTimeout(1000);
                }
            } finally {
                ventana.finCaptcha();
            }
        } else if (code != 0) {
            throw new IllegalStateException("Descargado, pero el like ha fallado: " + mensaje);
        }
        progreso.like("Like dado");
    }

    // ================================================================ datos

    /** Datos del modelo que la web incrusta en la página (window.__NUXT__). */
    private static JsonNode detalle(Page page) throws JsonProcessingException {
        Object s = page.evaluate("() => { const d = window.__NUXT__ && window.__NUXT__.data && window.__NUXT__.data[0];"
                + " return d && d.modelDetailData && !d.notFindView ? JSON.stringify(d.modelDetailData) : null; }");
        return s == null ? null : JSON.readTree((String) s);
    }

    /**
     * Modelos de "Uploads" del usuario (del más reciente al más antiguo), con
     * is_like según TU cuenta.
     *
     * @param limite cuántos de los últimos; 0 = todos (recorre todas las páginas)
     */
    private List<Modelo> listarModelos(Api api, String userId, int limite) throws InterruptedException {
        List<Modelo> out = new ArrayList<>();
        for (int pagina = 1; ; pagina++) {
            JsonNode data = api.get("/api/user/personal/index?page_size=12&user_id=" + userId + "&page=" + pagina);
            JsonNode lista = data.path("data");
            for (JsonNode n : lista) {
                out.add(new Modelo(n.path("mold_id").asLong(), n.path("title").asText(""),
                        n.path("is_like").asInt(0) == 1, n.path("is_premium").asBoolean(false)));
            }
            if (limite > 0 && out.size() >= limite) break; // con los 10 últimos basta una página
            int ultima = Math.max(1, data.path("page").path("last_page").asInt(1));
            if (limite <= 0) progreso.leyendoPaginas(pagina, ultima);
            if (pagina >= ultima || lista.isEmpty()) break;
            cancelacion.comprobar();
            Thread.sleep(800 + ThreadLocalRandom.current().nextInt(700)); // sin prisas
        }
        return limite > 0 && out.size() > limite ? new ArrayList<>(out.subList(0, limite)) : out;
    }

    private void guardarPortada(BrowserContext ctx, JsonNode det, Path dir) {
        String url = null;
        for (JsonNode im : det.path("images")) {
            if (im.path("is_main").asInt(0) == 1) { url = im.path("url").asText(null); break; }
        }
        if (url == null && det.path("images").size() > 0) url = det.path("images").get(0).path("url").asText(null);
        if (url == null || url.isBlank()) return;
        try {
            APIResponse r = ctx.request().get(url);
            if (r.ok()) Files.write(dir.resolve("portada" + extension(url)), r.body());
        } catch (Exception e) {
            progreso.nota("no se ha podido guardar la portada: " + e.getMessage());
        }
    }

    private static void guardarInfo(Page page, JsonNode det, Modelo m, Path dir) throws IOException {
        ObjectNode info = JSON.createObjectNode();
        info.put("id", m.id());
        info.put("title", det.path("title").asText(m.titulo()));
        info.put("author", det.path("user_display_name").asText(""));
        info.put("authorHandle", det.path("show_user_name").asText(""));
        info.put("url", urlModelo(m));
        info.put("category", det.path("category_nav").asText(""));
        Object licencia = page.evaluate("() => { const e = [...document.querySelectorAll('p,span,div')]"
                + ".find(x => x.children.length === 0 && /licensed under/i.test(x.textContent));"
                + " return e ? e.textContent.trim() : null; }");
        info.put("license", licencia == null ? "" : licencia.toString());
        info.set("tags", det.path("tags").isArray() ? det.path("tags").deepCopy() : JSON.createArrayNode());
        info.put("description", htmlATexto(det.path("desc").asText("")));
        info.put("downloadedAt", LocalDateTime.now().format(STAMP));
        JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("info.json").toFile(), info);
    }

    /** Carga descargados.txt (id ⇥ usuario ⇥ título ⇥ fecha). */
    private void leerRegistro() throws IOException {
        Path registro = rutas.getRegistro();
        Path antiguo = rutas.getRegistroAntiguo();
        // Antes vivía dentro de modelos-descargados/: se pasa fuera para que no se pierda al vaciarla
        if (Files.exists(antiguo)) {
            Files.write(registro, Files.readAllBytes(antiguo),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            Files.delete(antiguo);
            progreso.info("descargados.txt pasa a estar en " + registro.toAbsolutePath());
        }
        if (!Files.exists(registro)) return;
        for (String linea : Files.readAllLines(registro, StandardCharsets.UTF_8)) {
            String id = linea.split("\t", 2)[0].trim();
            if (id.matches("\\d+")) registrados.add(Long.parseLong(id));
        }
    }

    private void registrar(Modelo m, String usuario) {
        if (!registrados.add(m.id())) return;
        try {
            Files.writeString(rutas.getRegistro(),
                    m.id() + "\t" + usuario + "\t" + m.titulo().replaceAll("[\\t\\r\\n]+", " ")
                            + "\t" + LocalDateTime.now().format(STAMP) + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            progreso.nota("no se ha podido anotar en descargados.txt: " + e.getMessage());
        }
    }

    private static void registrarError(Path carpetaUsuario, Modelo m, String mensaje) {
        try {
            Files.writeString(carpetaUsuario.resolve("errores.txt"),
                    LocalDateTime.now().format(STAMP) + "  " + m.titulo() + "  " + urlModelo(m) + "\n    " + mensaje + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // no es grave
        }
    }

    // ================================================================ utilidades

    /** La sesión de MakerOnline es la cookie mo_access_token; la web la manda como cabecera Authorization. */
    private static String token(BrowserContext ctx) {
        for (Cookie c : ctx.cookies(WEB)) {
            if ("mo_access_token".equals(c.name) && c.value != null && !c.value.isBlank()) return c.value;
        }
        return null;
    }

    /** Aviso de cookies: tapa botones. Se cierra con "Necessary Only". */
    private static void cerrarCookies(Page page, int esperaMs) {
        Locator boton = page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Necessary Only"));
        try {
            if (esperaMs > 0) boton.waitFor(new Locator.WaitForOptions().setTimeout(esperaMs));
            if (boton.isVisible()) boton.click();
        } catch (TimeoutError e) {
            // no hay aviso
        }
    }

    /** Captura de la página en el momento del fallo: sin ventana es la única forma de ver qué pasó. */
    private static void captura(Page page, Path destino) {
        try {
            page.screenshot(new Page.ScreenshotOptions().setPath(destino).setFullPage(false));
        } catch (Exception ignored) {
            // si la página ya no responde, seguimos sin captura
        }
    }

    private static String icono(Locator svg) {
        Object href = svg.evaluate("el => { const u = el.querySelector('use');"
                + " return u ? (u.getAttribute('href') || u.getAttribute('xlink:href') || '') : ''; }");
        return href == null ? "" : href.toString();
    }

    private static String urlModelo(Modelo m) {
        String slug = URLEncoder.encode(m.titulo().replace('/', '-'), StandardCharsets.UTF_8).replace("+", "%20");
        return WEB + "/en/model/" + (slug.isEmpty() ? "model" : slug) + "/" + m.id() + ".html";
    }

    /**
     * Pausa entre descargas: al azar entre S y S+20 s (por defecto 10-30 s), siempre
     * igual aunque salgan captchas. Se cuenta desde que EMPEZÓ el modelo anterior, así
     * que lo que tardó en bajar ya cuenta como parte de la pausa. --espera=0 la quita.
     */
    private void pausa(Page page, int minimo, Estado est) {
        if (minimo <= 0) return;
        double objetivoS = minimo + ThreadLocalRandom.current().nextDouble(MARGEN_ESPERA);
        long restanteMs = Math.round(objetivoS * 1000) - (System.currentTimeMillis() - est.inicioUltimo);
        if (restanteMs <= 0) return;
        progreso.pausa(Math.round(restanteMs / 1000.0));
        // A trozos de medio segundo, para poder parar en mitad de la pausa
        long fin = System.currentTimeMillis() + restanteMs;
        while (System.currentTimeMillis() < fin) {
            cancelacion.comprobar();
            page.waitForTimeout(Math.min(500, fin - System.currentTimeMillis()));
        }
    }

    /** Nombre válido para una carpeta de Windows. */
    private static String limpio(String nombre, int max) {
        String s = nombre.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("\\s+", " ").trim();
        if (s.length() > max) s = s.substring(0, max).trim();
        s = s.replaceAll("[. ]+$", "");
        return s.isEmpty() ? "modelo" : s;
    }

    /** Igual que limpio() pero conservando la extensión. */
    private static String limpioArchivo(String nombre) {
        int punto = nombre.lastIndexOf('.');
        if (punto <= 0) return limpio(nombre, 100);
        return limpio(nombre.substring(0, punto), 100) + nombre.substring(punto).replaceAll("[^A-Za-z0-9.]", "");
    }

    private static String extension(String url) {
        String ruta = url.replaceFirst("[?#].*$", "");
        String ext = ruta.substring(ruta.lastIndexOf('.') + 1).toLowerCase();
        return ext.matches("jpe?g|png|webp|gif") ? "." + ext : ".jpg";
    }

    private static String htmlATexto(String html) {
        String t = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</p>", "\n").replaceAll("<[^>]+>", "");
        t = t.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return t.replaceAll("\n{3,}", "\n\n").trim();
    }

    private static Opciones leerArgs(String[] args) {
        String usuario = null;
        boolean simular = false, perfil = false, esperar = false;
        Modo modo = Modo.APARTADA;
        int max = Integer.MAX_VALUE, espera = ESPERA_POR_DEFECTO, ultimos = ULTIMOS_POR_DEFECTO;
        try {
            for (String a : args) {
                if (a.equals("--simular")) simular = true;
                else if (a.equals("--perfil")) perfil = true;
                else if (a.equals("--visible")) modo = Modo.VISIBLE;
                else if (a.equals("--sin-ventana")) modo = Modo.SIN_VENTANA;
                else if (a.equals("--esperar")) esperar = true;
                else if (a.equals("--todos")) ultimos = 0;
                else if (a.startsWith("--ultimos=")) ultimos = Integer.parseInt(a.substring(10));
                else if (a.startsWith("--max=")) max = Integer.parseInt(a.substring(6));
                else if (a.startsWith("--espera=")) espera = Integer.parseInt(a.substring(9));
                else if (a.startsWith("--")) {
                    System.err.println("Opción desconocida: " + a);
                    return null;
                } else if (usuario == null) usuario = a;
                else {
                    System.err.println("Solo se admite un usuario por argumento (para varios, usa usuarios.json).");
                    return null;
                }
            }
        } catch (NumberFormatException e) {
            System.err.println("Número no válido en --ultimos, --max o --espera");
            return null;
        }
        if (max < 1 || espera < 0 || ultimos < 0) return null;
        return new Opciones(usuario, ultimos, simular, max, espera, perfil, modo, esperar, List.of(), List.of());
    }

    private static void ayuda() {
        System.out.println("""
                Uso: Downloader [usuario] [opciones]
                  sin usuario   repasa todos los de usuarios.json
                  usuario       solo ese: nombre, @usuario_1234567, URL del perfil o id
                Opciones:
                  --ultimos=N   cuántos de los últimos modelos de cada usuario se miran (por defecto 10)
                  --todos       mira todos los modelos de cada usuario (más lento)
                  --simular     solo lista lo que descargaría (no descarga ni da like)
                  --max=N       como mucho N descargas en esta ejecución
                  --espera=S    pausa entre descargas: entre S y S+20 s (por defecto 10, o sea 10-30 s)
                  --perfil      descarga también el perfil de impresión (.3mf)
                  --visible     ventana de Chrome a la vista todo el rato
                  --sin-ventana Chrome sin ventana; si sale un captcha, se para
                  --esperar     si el Uploader está en marcha, espera a que termine
                Por defecto Chrome trabaja fuera de la pantalla y solo aparece si hay captcha.
                Ejemplo: -Dexec.args="--simular"
                """);
    }
}
