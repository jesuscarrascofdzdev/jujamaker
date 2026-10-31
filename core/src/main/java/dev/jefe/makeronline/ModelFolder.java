package dev.jefe.makeronline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Una carpeta de subidas/pendientes/ ya validada. Los nombres de los archivos dan igual;
 * dentro tiene que haber:
 * <ul>
 *   <li>al menos un modelo: .3mf o .stl;</li>
 *   <li>al menos una imagen: .jpg, .jpeg o .png. La portada es la que se llama como la carpeta,
 *       "portada" o "cover"; si no hay ninguna así, la primera por orden alfabético;</li>
 *   <li>los textos: un .json (con el formato de info.json) o, si no hay .json, un .txt
 *       (ver TextosModelo).</li>
 * </ul>
 * Se ignoran los archivos que crea el propio programa (error.png, resultado.png, error.txt)
 * y los del sistema (desktop.ini, Thumbs.db, ocultos).
 *
 * @param modelos  los .3mf y .stl, por orden alfabético
 * @param imagenes las imágenes, con la portada la primera
 * @param textos   el .json o .txt de donde salen los textos
 */
public record ModelFolder(Path dir, String name, List<Path> modelos, List<Path> imagenes, Path textos,
                          ModelMeta meta) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> IGNORADOS = Set.of(
            "error.png", "resultado.png", "error.txt", "errores.txt", "desktop.ini", "thumbs.db");
    private static final Set<String> NOMBRES_PORTADA = Set.of("portada", "cover");
    private static final Comparator<Path> POR_NOMBRE =
            Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT));

    /** Lee y valida la carpeta. Lanza IllegalStateException con todos los problemas juntos. */
    public static ModelFolder load(Path dir) throws IOException {
        String name = dir.getFileName().toString();
        List<String> errors = new ArrayList<>();

        List<Path> archivos = archivos(dir);
        List<Path> modelos = conExtension(archivos, "3mf", "stl");
        List<Path> imagenes = portadaPrimero(conExtension(archivos, "jpg", "jpeg", "png"), name);
        if (modelos.isEmpty()) errors.add("Falta el modelo: un archivo .3mf o .stl");
        if (imagenes.isEmpty()) errors.add("Falta la portada: una imagen .jpg o .png");

        // Los textos: el .json si lo hay; si no, el .txt
        Path textos = null;
        List<Path> jsons = conExtension(archivos, "json");
        List<Path> txts = conExtension(archivos, "txt");
        if (jsons.size() > 1) errors.add("Hay varios .json; deja solo uno con los textos: " + nombres(jsons));
        else if (jsons.size() == 1) textos = jsons.get(0);
        else if (txts.size() > 1) errors.add("Hay varios .txt; deja solo uno con los textos: " + nombres(txts));
        else if (txts.size() == 1) textos = txts.get(0);
        else errors.add("Faltan los textos: un .json (como info.json) o un .txt con título, descripción "
                    + "y etiquetas");

        ModelMeta meta = null;
        if (textos != null) {
            String archivo = textos.getFileName().toString();
            try {
                meta = leerTextos(textos);
                for (String e : meta.validate()) errors.add(archivo + ": " + e);
            } catch (JsonProcessingException e) {
                String donde = e.getLocation() == null ? "" : " en la línea " + e.getLocation().getLineNr();
                errors.add(archivo + " no es válido" + donde + " (revisa comas y comillas): " + e.getOriginalMessage());
            } catch (IOException e) {
                errors.add("No se puede leer " + archivo + ": " + e.getMessage());
            } catch (IllegalStateException e) {
                errors.add(archivo + ": " + e.getMessage().replace("\n", "\n" + archivo + ": "));
            }
        }

        if (!errors.isEmpty()) throw new IllegalStateException(String.join("\n", errors));
        return new ModelFolder(dir, name, modelos, imagenes, textos, meta);
    }

    /**
     * Los archivos de textos de la carpeta, con las mismas reglas que load(): los .json si hay
     * alguno; si no, los .txt. Lo normal es uno; si hay más, load() lo avisa como problema.
     */
    public static List<Path> archivosDeTextos(Path dir) throws IOException {
        List<Path> archivos = archivos(dir);
        List<Path> jsons = conExtension(archivos, "json");
        return jsons.isEmpty() ? conExtension(archivos, "txt") : jsons;
    }

    /**
     * Lee los textos de un .json o un .txt (según la extensión), sin comprobar que estén completos.
     *
     * @throws IOException           si no se puede leer o el .json está mal escrito
     * @throws IllegalStateException si el .txt tiene líneas que no se entienden
     */
    public static ModelMeta leerTextos(Path archivo) throws IOException {
        return extension(archivo).equals("json")
                ? JSON.readValue(archivo.toFile(), ModelMeta.class)
                : TextosModelo.leer(archivo);
    }

    /** Las imágenes de la carpeta, con la portada la primera (aunque la carpeta esté incompleta). */
    public static List<Path> imagenesDe(Path dir) throws IOException {
        return portadaPrimero(conExtension(archivos(dir), "jpg", "jpeg", "png"), dir.getFileName().toString());
    }

    /** Los .3mf y .stl de la carpeta, por nombre (aunque la carpeta esté incompleta). */
    public static List<Path> modelosDe(Path dir) throws IOException {
        return conExtension(archivos(dir), "3mf", "stl");
    }

    /** Los archivos de la carpeta que cuentan (sin los del programa ni los del sistema), por nombre. */
    private static List<Path> archivos(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(Files::isRegularFile).filter(p -> !ignorado(p)).sorted(POR_NOMBRE).toList();
        }
    }

    /** La portada (la primera imagen). */
    public Path portada() {
        return imagenes.get(0);
    }

    /** El .3mf que va como perfil de impresión ("Print Profile Files"), o null si solo hay .stl. */
    public Path perfil() {
        return modelos.stream().filter(ModelFolder::es3mf).findFirst().orElse(null);
    }

    /** El archivo que va en "Model Files": el primer .stl si lo hay; si no, el .3mf del perfil. */
    public Path archivoModelo() {
        return modelos.stream().filter(p -> !es3mf(p)).findFirst().orElse(modelos.get(0));
    }

    /**
     * Lo que hay en la carpeta pero de momento no se sube: falta comprobar en la web cómo
     * admite el formulario varias imágenes y varios modelos.
     */
    public List<String> avisos() {
        List<String> avisos = new ArrayList<>();
        for (String d : CatalogoMakerOnline.desconocidos(meta)) {
            avisos.add("Ojo: " + d + "; si la web no lo tiene, fallará al subir.");
        }
        int imagenesSinSubir = imagenes.size() - 1;
        if (imagenesSinSubir > 0) {
            avisos.add("De momento solo se sube la portada (" + portada().getFileName() + "); "
                    + imagenesSinSubir + (imagenesSinSubir == 1 ? " imagen más se queda" : " imágenes más se quedan")
                    + " sin subir.");
        }
        List<Path> modelosSinSubir = modelos.stream()
                .filter(p -> !p.equals(perfil()) && !p.equals(archivoModelo())).toList();
        if (!modelosSinSubir.isEmpty()) {
            avisos.add("De momento solo se sube un modelo por zona; sin subir: " + nombres(modelosSinSubir) + ".");
        }
        return avisos;
    }

    private static boolean ignorado(Path p) {
        String f = p.getFileName().toString();
        return f.startsWith(".") || f.startsWith("~$") || IGNORADOS.contains(f.toLowerCase(Locale.ROOT));
    }

    private static boolean es3mf(Path p) {
        return extension(p).equals("3mf");
    }

    private static String extension(Path p) {
        String f = p.getFileName().toString();
        int punto = f.lastIndexOf('.');
        return punto < 0 ? "" : f.substring(punto + 1).toLowerCase(Locale.ROOT);
    }

    private static String sinExtension(Path p) {
        String f = p.getFileName().toString();
        int punto = f.lastIndexOf('.');
        return punto < 0 ? f : f.substring(0, punto);
    }

    private static List<Path> conExtension(List<Path> archivos, String... exts) {
        return archivos.stream().filter(p -> List.of(exts).contains(extension(p))).toList();
    }

    /** Pone delante la imagen que se llama como la carpeta, "portada" o "cover", si la hay. */
    private static List<Path> portadaPrimero(List<Path> imagenes, String carpeta) {
        List<Path> out = new ArrayList<>(imagenes);
        for (Path p : imagenes) {
            String base = sinExtension(p).toLowerCase(Locale.ROOT);
            if (base.equals(carpeta.toLowerCase(Locale.ROOT)) || NOMBRES_PORTADA.contains(base)) {
                out.remove(p);
                out.add(0, p);
                break;
            }
        }
        return List.copyOf(out);
    }

    private static String nombres(List<Path> archivos) {
        return String.join(", ", archivos.stream().map(p -> p.getFileName().toString()).toList());
    }
}
