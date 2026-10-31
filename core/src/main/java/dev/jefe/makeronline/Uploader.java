package dev.jefe.makeronline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.AriaRole;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * El agente.
 *
 * Recorre subidas/pendientes/, valida cada carpeta, rellena el formulario de
 * MakerOnline y lo guarda como BORRADOR (draft). Después mueve la carpeta a
 * subidas/subidos/ (con la fecha delante) o a subidas/errores/ (con error.txt
 * y una captura del momento del fallo).
 *
 * Argumentos (opcionales):
 *   --revisar   con todo subido, se para antes de guardar cada borrador para que mires
 *               el formulario: ENTER lo guarda; "no" lo descarta (se queda en pendientes/).
 *   --esperar   si el Downloader (u otro programa del agente) está en marcha,
 *               espera a que termine en vez de salir.
 *   nombre ...  solo procesa esas carpetas de pendientes/ (sin nombres, todas).
 *               Ejemplo: --revisar llavero-gato
 *
 * Para pararlo sin dejar nada a medias: escribir "parar" y ENTER en la consola. Lo que
 * no se haya guardado se queda en pendientes/.
 */
public class Uploader {

    private static final String UPLOAD_URL = "https://www.makeronline.com/en/upload";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Intentos (uno por segundo) para mover una carpeta que Windows tiene bloqueada. */
    private static final int INTENTOS_MOVER = 5;

    /**
     * Opciones de una ejecución.
     *
     * @param revisar  se para justo antes de guardar cada borrador (--revisar)
     * @param carpetas solo estas carpetas de pendientes/; vacía = todas
     * @param esperar  si otro programa del agente está en marcha, espera a que termine (--esperar)
     */
    public record Opciones(boolean revisar, List<String> carpetas, boolean esperar) {}

    /**
     * Cómo ha ido una ejecución.
     *
     * @param subidos  guardados como borrador
     * @param conError fallaron al subir (están en errores/ con error.txt y una captura)
     * @param invalidos no pasaron la validación (también están en errores/)
     * @param sinSubir  no se han guardado porque se ha parado o se han descartado al revisar
     *                  (siguen en pendientes/)
     * @param parado    true si se ha parado a petición de la persona
     * @param carpetasConError dónde han quedado en errores/ las que han fallado o no eran válidas
     *                         (cada una con error.txt y, si falló al subir, error.png)
     */
    public record Resumen(int subidos, int conError, int invalidos, int sinSubir, boolean parado,
                          List<Path> carpetasConError) {}

    /** La persona ha decidido no guardar este borrador al revisarlo. */
    private static final class ModeloDescartado extends RuntimeException {
        ModeloDescartado() { super("descartado al revisar"); }
    }

    /** Dónde están la sesión de Chrome y las carpetas de subidas. */
    private final Rutas rutas;
    /** A quién se le cuenta lo que va pasando (terminal o app). */
    private final Progreso progreso;
    /** Si se pide parar, se para en el siguiente punto seguro. */
    private final Cancelacion cancelacion;
    /** Pregunta si se guarda cada borrador (solo con --revisar). */
    private final Revision revision;

    public Uploader(Rutas rutas, Progreso progreso, Cancelacion cancelacion, Revision revision) {
        this.rutas = rutas;
        this.progreso = progreso;
        this.cancelacion = cancelacion;
        this.revision = revision;
    }

    /** Sin parar ni revisión (con --revisar, guarda sin preguntar). */
    public Uploader(Rutas rutas, Progreso progreso) {
        this(rutas, progreso, new Cancelacion(), modelo -> true);
    }

    public static void main(String[] args) throws Exception {
        Opciones op = leerArgs(args);
        Rutas rutas = Rutas.carpetaActual();
        Progreso progreso = new ProgresoConsola();
        Cancelacion cancelacion = new Cancelacion();
        // Solo un programa del agente a la vez: comparten chrome-profile/
        try (AgentLock lock = AgentLock.acquire(rutas.getCandado(), "Uploader", op.esperar(), progreso)) {
            EntradaConsola.iniciar(cancelacion, progreso);
            new Uploader(rutas, progreso, cancelacion, new RevisionConsola(progreso, cancelacion)).ejecutar(op);
        } catch (AgenteException e) {
            progreso.error(e.getMessage());
            System.exit(e.getCodigoSalida());
        }
    }

    private static Opciones leerArgs(String[] args) {
        List<String> lista = List.of(args);
        return new Opciones(
                lista.contains("--revisar"),
                lista.stream().filter(a -> !a.startsWith("--")).toList(), // nombres de carpeta
                lista.contains("--esperar"));
    }

    /**
     * Valida y sube como borrador los modelos de pendientes/.
     *
     * @throws AgenteException si no hay sesión guardada
     */
    public Resumen ejecutar(Opciones op) throws Exception {
        Path pendientes = rutas.getPendientes();
        Files.createDirectories(pendientes);
        Files.createDirectories(rutas.getSubidos());
        Files.createDirectories(rutas.getErrores());

        if (!Files.isDirectory(rutas.getPerfilChrome())) {
            throw new AgenteException("No hay sesión guardada. Ejecuta primero SaveSession.");
        }

        // 1) Validar TODO antes de abrir el navegador
        List<ModelFolder> listos = new ArrayList<>();
        List<Path> carpetas;
        try (Stream<Path> s = Files.list(pendientes)) {
            carpetas = s.filter(Files::isDirectory).sorted().toList();
        }

        // Si se pasan nombres de carpeta, solo se procesan esas
        List<String> nombres = op.carpetas();
        if (!nombres.isEmpty()) {
            for (String n : nombres) {
                if (!Files.isDirectory(pendientes.resolve(n))) progreso.error("No existe en pendientes: " + n);
            }
            carpetas = carpetas.stream().filter(d -> nombres.contains(d.getFileName().toString())).toList();
        }

        if (carpetas.isEmpty()) {
            progreso.info("No hay nada en " + pendientes.toAbsolutePath());
            return new Resumen(0, 0, 0, 0, false, List.of());
        }
        int invalidos = 0;
        List<Path> enErrores = new ArrayList<>();
        for (Path dir : carpetas) {
            try {
                listos.add(ModelFolder.load(dir));
                progreso.correcto(dir.getFileName() + " validado");
            } catch (IllegalStateException | IOException e) {
                progreso.error(dir.getFileName() + ":\n   " + e.getMessage().replace("\n", "\n   "));
                anotar(enErrores, moverAErrores(dir, e.getMessage()));
                invalidos++;
            }
        }
        if (listos.isEmpty()) {
            progreso.info("Ningún modelo válido. Revisa " + rutas.getErrores() + ".");
            return new Resumen(0, 0, invalidos, 0, false, enErrores);
        }

        // 2) Subir los válidos
        int ok = 0, ko = 0, sinSubir = 0;
        boolean parado = false;
        try (Playwright pw = Playwright.create();
             BrowserContext ctx = Browsers.openChrome(pw, rutas.getPerfilChrome(), 150)) {
            ctx.setDefaultTimeout(30_000);

            for (int i = 0; i < listos.size() && !parado; i++) {
                if (cancelacion.pedida()) {
                    parado = true;
                    sinSubir += listos.size() - i;
                    break;
                }
                ModelFolder m = listos.get(i);
                progreso.modelo(i + 1, listos.size(), m.name());
                Page page = ctx.newPage();
                boolean guardado = false;
                try {
                    subir(page, m, op.revisar());
                    guardado = true;
                    captura(page, m.dir().resolve("resultado.png"));
                } catch (CanceladoException e) {
                    // No es un error: el borrador no se ha guardado y la carpeta se queda en pendientes/
                    parado = true;
                    sinSubir += listos.size() - i;
                    progreso.info("⏹ " + m.name() + " no se ha guardado: sigue en pendientes/.");
                } catch (ModeloDescartado e) {
                    sinSubir++;
                    progreso.saltado("No se guarda " + m.name() + ": sigue en pendientes/.");
                } catch (Exception e) {
                    progreso.error("Error: " + e.getMessage());
                    captura(page, m.dir().resolve("error.png"));
                    // Primero se cierra la pestaña: así Chrome deja de leer los archivos y Windows deja moverlos
                    cerrar(page);
                    anotar(enErrores, moverAErrores(m.dir(), e.toString()));
                    ko++;
                } finally {
                    cerrar(page);
                }
                if (guardado) {
                    ok++;
                    try {
                        Path destino = mover(m.dir(), rutas.getSubidos());
                        progreso.correcto("Borrador guardado → " + destino);
                    } catch (IOException e) {
                        // El borrador YA está en MakerOnline: si la carpeta se queda en pendientes/,
                        // la próxima vez se subiría otra vez
                        progreso.error("El borrador se ha guardado en MakerOnline, pero no he podido mover "
                                + m.dir() + " a subidos/: " + e.getMessage()
                                + "\n  Muévela tú (o bórrala de pendientes/) para que no se vuelva a subir.");
                    }
                }
            }
        }
        progreso.info(String.format("%n%s: %d subidos, %d con error.", parado ? "Parado" : "Terminado", ok, ko)
                + (sinSubir > 0 ? " " + sinSubir + " siguen en pendientes/." : ""));
        return new Resumen(ok, ko, invalidos, sinSubir, parado, enErrores);
    }

    /** Cierra la pestaña. Se puede llamar varias veces. */
    private static void cerrar(Page page) {
        if (!page.isClosed()) page.close();
    }

    /** Cierra el aviso de cookies si aparece: tapa la ventana de licencia y bloquea los clics. */
    private void cerrarCookies(Page page) {
        Locator boton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName("Necessary Only"));
        try {
            boton.waitFor(new Locator.WaitForOptions().setTimeout(3_000));
            boton.click();
            paso("Aviso de cookies cerrado");
        } catch (TimeoutError e) {
            // no hay aviso de cookies
        }
    }

    /**
     * Rellena el formulario completo y guarda como borrador.
     *
     * @throws CanceladoException si se pide parar antes de guardar (no se guarda nada)
     * @throws ModeloDescartado   si al revisar la persona decide no guardarlo
     */
    private void subir(Page page, ModelFolder m, boolean revisar) {
        ModelMeta meta = m.meta();
        for (String aviso : m.avisos()) progreso.nota(aviso);
        // Se crea antes de elegir ningún archivo para que vea todas las subidas.
        // No hace falta cerrarlo: deja de escuchar cuando se cierra la pestaña.
        VigilanteSubidas subidas = new VigilanteSubidas(page, progreso, cancelacion);

        paso("Abriendo formulario");
        page.navigate(UPLOAD_URL);

        // --- Licencia (ventana que sale al entrar) ---
        Locator licencia = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("Choose a CC license"));
        try {
            licencia.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        } catch (TimeoutError e) {
            throw new IllegalStateException(
                    "No se ha cargado el formulario de subida. ¿Ha caducado la sesión? Ejecuta SaveSession.");
        }
        cerrarCookies(page);
        paso("Licencia: " + meta.licenseOrDefault());
        licencia.click();
        // El título exacto de la opción ("CC BY", "CC0"…); si no aparece (p. ej. "CC0 (aka CC Zero)" de los
        // info.json antiguos, que es el principio de su descripción), lo que contenga ese texto, como antes
        Locator opcion = page.getByText(meta.licenseOrDefault(), new Page.GetByTextOptions().setExact(true)).first();
        try {
            opcion.waitFor(new Locator.WaitForOptions().setTimeout(5_000));
        } catch (TimeoutError e) {
            opcion = page.getByText(meta.licenseOrDefault()).first();
        }
        opcion.click();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Submit")).click();

        // --- Portada ---
        paso("Portada: " + m.portada().getFileName());
        elegirArchivo(page, page.locator(".el-upload-dragger").first(), m.portada());
        subidas.esperar("la portada");

        // --- Título ---
        paso("Título");
        Locator titulo = page.locator(".el-form-item")
                .filter(new Locator.FilterOptions().setHasText("Model Title"))
                .locator("input, textarea").first();
        if (titulo.count() == 0) {
            titulo = page.locator("form").getByRole(AriaRole.TEXTBOX).nth(1); // selector grabado por codegen
        }
        titulo.fill(meta.title());

        // --- Categoría (2 niveles) ---
        List<String> categoria = meta.categoryOrDefault();
        paso("Categoría: " + String.join(" > ", categoria) + (meta.sinCategoria() ? " (por defecto)" : ""));
        Locator selectorTipo = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("First level classification/"));
        Locator primerNivel = page.getByText(categoria.get(0),
                new Page.GetByTextOptions().setExact(true));
        // el desplegable a veces no abre con el primer clic: se reintenta
        for (int intento = 1; ; intento++) {
            selectorTipo.scrollIntoViewIfNeeded();
            selectorTipo.click();
            try {
                primerNivel.waitFor(new Locator.WaitForOptions().setTimeout(5_000));
                break;
            } catch (TimeoutError e) {
                if (intento >= 3) throw e;
                page.waitForTimeout(1000);
            }
        }
        for (String nivel : categoria) {
            page.getByText(nivel, new Page.GetByTextOptions().setExact(true)).click();
        }

        // --- Tags ---
        paso("Tags: " + tagsLimpios(meta.tags()));
        Locator tags = page.getByRole(AriaRole.TEXTBOX,
                new Page.GetByRoleOptions().setName("*Press the Enter key to"));
        for (String tag : tagsLimpios(meta.tags())) {
            tags.click();
            tags.fill(tag);
            tags.press("Enter");
        }

        // --- Visibilidad y tipo de impresión ---
        paso("Public + " + meta.printTypeOrDefault());
        page.locator("label").filter(new Locator.FilterOptions().setHasText("Public")).click();
        page.locator("label").filter(new Locator.FilterOptions().setHasText(meta.printTypeOrDefault())).click();

        // --- Descripción (editor Quill): se escribe de verdad, línea a línea ---
        paso("Descripción");
        page.locator(".ql-editor").click();
        String[] lineas = meta.description().split("\\R", -1);
        for (int i = 0; i < lineas.length; i++) {
            if (i > 0) page.keyboard().press("Enter");
            if (!lineas[i].isEmpty()) page.keyboard().insertText(lineas[i]);
        }

        // --- Paso 2: archivos ---
        cancelacion.comprobar(); // punto seguro: aún no se ha subido ningún archivo grande
        paso("Siguiente paso: archivos");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Next Step, Add Files")).click();
        try {
            page.locator(".el-upload").filter(new Locator.FilterOptions().setHasText("Supported files: stl"))
                    .first().waitFor(new Locator.WaitForOptions().setTimeout(15_000));
        } catch (TimeoutError e) {
            String avisos = String.join(" | ",
                    page.locator(".el-message, .el-form-item__error, .el-message-box").allInnerTexts());
            throw new IllegalStateException("No avanza al paso de archivos. Avisos del formulario: "
                    + (avisos.isBlank() ? "(ninguno)" : avisos));
        }

        if (m.perfil() != null) {
            paso("Perfil de impresión (3mf): " + m.perfil().getFileName());
            elegirArchivo(page, zonaSubida(page, "only supports single 3mf"), m.perfil());
            // No se espera aquí: el .3mf sigue subiendo mientras se rellena el resto
            // (comprobado que funciona) y se espera a todo junto antes de guardar.

            paso("Título del perfil de impresión");
            Locator tituloPerfil = page.locator(".el-form-item")
                    .filter(new Locator.FilterOptions().setHasText("Print Profile Title"))
                    .locator("input, textarea").first();
            try {
                tituloPerfil.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            } catch (TimeoutError e) {
                throw new IllegalStateException(
                        "No encuentro el campo 'Print Profile Title'. Graba el selector con Codegen.");
            }
            tituloPerfil.fill(meta.title());

            paso("Imagen del perfil de impresión: " + m.portada().getFileName());
            Locator zonaFotos = page.locator(".el-form-item")
                    .filter(new Locator.FilterOptions().setHasText("Print Profile Pictures"))
                    .locator(".el-upload").first();
            try {
                zonaFotos.waitFor(new Locator.WaitForOptions().setTimeout(15_000));
            } catch (TimeoutError e) {
                throw new IllegalStateException(
                        "No encuentro la zona 'Print Profile Pictures'. Graba el selector con Codegen.");
            }
            elegirArchivo(page, zonaFotos, m.portada());
        }
        paso("Archivo del modelo: " + m.archivoModelo().getFileName());
        elegirArchivo(page, zonaSubida(page, "Supported files: stl"), m.archivoModelo());

        // --- Esperar a que TODO esté subido (si algo falla o se atasca, no se guarda) ---
        subidas.esperar("los archivos");

        if (revisar && !revision.confirmar(m.name())) throw new ModeloDescartado();

        // --- Guardar como borrador ---
        // Último punto seguro: una vez pulsado "Save to draft" ya no se para hasta confirmarlo
        cancelacion.comprobar();
        guardarBorrador(page);
    }

    /**
     * Pulsa "Save to draft" y confirma con la respuesta de MakerOnline (comprobado el 31/10/2026):
     * POST /api/mold/save-draft responde {code: 0, message: "Successful operation", data: {id, ...}}
     * y después la página salta a /en/user/uploads/draft.html.
     *
     * @throws IllegalStateException si no responde o no lo guarda
     */
    static void guardarBorrador(Page page, Progreso progreso) {
        progreso.paso("Guardando borrador");
        Locator boton = page.getByRole(AriaRole.BUTTON,
                new Page.GetByRoleOptions().setName(Pattern.compile("draft", Pattern.CASE_INSENSITIVE))).first();
        // La respuesta se lee en cuanto llega: justo después la web salta a otra página
        // y Chrome puede dejar de tenerla.
        AtomicReference<String> cuerpo = new AtomicReference<>();
        Response r;
        try {
            r = page.waitForResponse(resp -> {
                if (!resp.url().contains("/api/mold/save-draft")) return false;
                try {
                    cuerpo.set(resp.text());
                } catch (Exception e) {
                    // no se ha podido leer: se confirma con el salto de página (abajo)
                }
                return true;
            }, new Page.WaitForResponseOptions().setTimeout(60_000), boton::click);
        } catch (TimeoutError e) {
            throw new IllegalStateException("He pulsado 'Save to draft', pero MakerOnline no ha respondido en 60 s");
        }
        if (!r.ok()) {
            throw new IllegalStateException("MakerOnline no ha guardado el borrador (HTTP " + r.status() + ")");
        }

        JsonNode j = null;
        try {
            if (cuerpo.get() != null) j = JSON.readTree(cuerpo.get());
        } catch (Exception e) {
            // no es JSON: se confirma con el salto de página
        }
        if (j != null && j.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("MakerOnline no ha guardado el borrador: "
                    + j.path("message").asText("código " + j.path("code").asText("?")));
        }

        // Cuando guarda bien, la web salta sola a la lista de borradores
        boolean salto;
        try {
            page.waitForURL("**/user/uploads/draft.html", new Page.WaitForURLOptions().setTimeout(15_000));
            salto = true;
        } catch (TimeoutError e) {
            salto = false;
        }
        if (j != null) {
            progreso.paso("MakerOnline confirma el borrador (id " + j.path("data").path("id").asText("?") + ")");
        } else if (salto) {
            progreso.paso("MakerOnline confirma el borrador (ha saltado a tus borradores)");
        } else {
            throw new IllegalStateException("No he podido confirmar que MakerOnline haya guardado el borrador."
                    + " Mira en tus borradores si está antes de volver a subirlo.");
        }
    }

    private void guardarBorrador(Page page) {
        guardarBorrador(page, progreso);
    }

    // ----------------------------------------------------------------- utilidades

    /** Parte cada entrada por comas ("a, b" → "a", "b"), quita espacios, vacíos y repetidos. */
    private static List<String> tagsLimpios(List<String> tags) {
        return tags.stream()
                .flatMap(t -> Stream.of(t.split(",")))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .distinct()
                .toList();
    }

    /** Zona de subida (.el-upload) que contiene el texto indicado. */
    private static Locator zonaSubida(Page page, String texto) {
        return page.locator(".el-upload").filter(new Locator.FilterOptions().setHasText(texto)).first();
    }

    /** Hace clic como una persona y responde al selector de archivos del navegador. */
    private static void elegirArchivo(Page page, Locator zona, Path archivo) {
        FileChooser fc = page.waitForFileChooser(zona::click);
        fc.setFiles(archivo);
    }

    private void paso(String texto) {
        progreso.paso(texto);
    }

    private static void captura(Page page, Path destino) {
        try {
            page.screenshot(new Page.ScreenshotOptions().setPath(destino).setFullPage(true));
        } catch (Exception ignored) {
            // si la página ya no responde, seguimos sin captura
        }
    }

    /** Escribe error.txt y mueve la carpeta a errores/. Devuelve dónde ha quedado, o null si no se ha podido. */
    private Path moverAErrores(Path dir, String mensaje) {
        try {
            Files.writeString(dir.resolve("error.txt"),
                    LocalDateTime.now().format(STAMP) + "\n" + mensaje + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Path destino = mover(dir, rutas.getErrores());
            progreso.info("   Movido a " + destino);
            return destino;
        } catch (IOException e) {
            progreso.error("No se pudo mover a errores/: " + e.getMessage());
            return null;
        }
    }

    private static void anotar(List<Path> lista, Path carpeta) {
        if (carpeta != null) lista.add(carpeta);
    }

    /**
     * Mueve la carpeta a destino con la fecha delante: 2026-09-23_2145_modelo-a
     *
     * En Windows no se puede mover una carpeta con archivos abiertos. Justo después de
     * cerrar la pestaña, Chrome puede tardar un momento en soltarlos: se reintenta unos segundos.
     */
    public static Path mover(Path dir, Path carpetaDestino) throws IOException {
        Path destino = carpetaDestino.resolve(LocalDateTime.now().format(STAMP) + "_" + dir.getFileName());
        int n = 2;
        while (Files.exists(destino)) {
            destino = carpetaDestino.resolve(LocalDateTime.now().format(STAMP) + "_" + dir.getFileName() + "_" + n++);
        }
        for (int intento = 1; ; intento++) {
            try {
                return Files.move(dir, destino);
            } catch (AccessDeniedException e) {
                if (intento >= INTENTOS_MOVER) {
                    throw new IOException("la carpeta está en uso (¿la tienes abierta en el Explorador o en otro programa?): "
                            + dir, e);
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrumpido mientras se movía " + dir, ie);
                }
            }
        }
    }
}
