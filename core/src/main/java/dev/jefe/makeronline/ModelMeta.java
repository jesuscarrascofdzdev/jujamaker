package dev.jefe.makeronline;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Los textos de cada modelo: salen del .json (info.json) o del .txt (ver TextosModelo).
 *
 * Obligatorios: title, description, tags.
 * Opcionales:   category  (por defecto Toys&Games > Action Figures; se puede cambiar luego en el borrador)
 * Opcionales:   license   (por defecto "CC0"; ver CatalogoMakerOnline)
 *               printType (por defecto "FDM")
 */
public record ModelMeta(
        String title,
        String description,
        List<String> tags,
        List<String> category,
        String license,
        String printType
) {
    public String licenseOrDefault()   { return blank(license)   ? CatalogoMakerOnline.LICENCIA_POR_DEFECTO : license; }
    public String printTypeOrDefault() { return blank(printType) ? CatalogoMakerOnline.TIPO_POR_DEFECTO : printType; }

    /** La categoría, o la de por defecto si no hay (o solo está la de ejemplo de la plantilla). */
    public List<String> categoryOrDefault() { return sinCategoria() ? CatalogoMakerOnline.CATEGORIA_POR_DEFECTO : category; }

    /** Si no se ha puesto categoría: entonces se usa la de por defecto. */
    public boolean sinCategoria() {
        return category == null || category.isEmpty() || category.stream().allMatch(ModelMeta::blank);
    }

    /** Devuelve la lista de problemas; vacía si todo está bien. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        // Cada mensaje dice cómo se llama el dato en el .json y en el .txt
        if (blank(title)) errors.add("Falta el título (\"title\" en el .json, \"Título:\" en el .txt)");
        if (blank(description)) errors.add("Falta la descripción (\"description\" en el .json, \"Descripción:\" en el .txt)");
        if (tags == null || tags.isEmpty() || tags.stream().anyMatch(ModelMeta::blank))
            errors.add("Faltan las etiquetas: al menos una y ninguna vacía (\"tags\" en el .json, \"Etiquetas:\" en el .txt)");
        if (!sinCategoria() && (category.stream().anyMatch(ModelMeta::blank)
                || !CatalogoMakerOnline.categoriaCompleta(category)))
            errors.add("La categoría tiene que tener 2 niveles: [\"Toys&Games\", \"Action Figures\"] en el .json, "
                    + "\"Categoría: Toys&Games > Action Figures\" en el .txt (solo 1 si no tiene subcategorías, "
                    + "como Creative Kit Model)");
        boolean conPlantilla = Stream.of(Stream.of(title, description),
                        tags == null ? Stream.<String>empty() : tags.stream(),
                        sinCategoria() ? Stream.<String>empty() : category.stream())
                .flatMap(x -> x).anyMatch(ModelMeta::sinRellenar);
        if (conPlantilla) errors.add("Lo que va entre corchetes [ ] cuenta como sin rellenar: cámbialo por lo tuyo");
        return errors;
    }

    private static boolean sinRellenar(String s) {
        if (s == null) return false;
        String t = s.strip();
        return t.startsWith("[") && t.endsWith("]");
    }

    /**
     * Vacío o sin rellenar: lo que va entre corchetes, como "[título del modelo]", es el texto
     * de ejemplo de las plantillas y no cuenta.
     */
    private static boolean blank(String s) {
        return s == null || s.isBlank() || sinRellenar(s);
    }
}
