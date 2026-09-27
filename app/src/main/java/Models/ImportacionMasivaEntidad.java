package Models;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Locale;

/**
 * Targets of the bulk import surface ({@code /api/app/importacion-masiva}).
 *
 * <p>One constant per importable catalog, kept in the SAME order the
 * {@code /api/app/export} datasets and the navigable pages use
 * (clientes → artículos → CABYS → precios) so the Spanish UX of the import
 * screen mirrors the export/reporting one.</p>
 *
 * <p>The wire keys are the lowercase, unaccented forms used in the export
 * dataset names, so a client can round-trip
 * {@code POST /api/app/export?dataset=articulos} →
 * {@code POST /api/app/importacion-masiva?entidad=articulos} with the
 * template headers unchanged.</p>
 */
public enum ImportacionMasivaEntidad {

    CLIENTES("clientes", "Clientes"),
    ARTICULOS("articulos", "Artículos"),
    CABYS("cabys", "CABYS"),
    PRECIOS("precios", "Precios");

    private final String clave;
    private final String etiqueta;

    ImportacionMasivaEntidad(String clave, String etiqueta) {
        this.clave = clave;
        this.etiqueta = etiqueta;
    }

    /** Lowercase wire key, e.g. {@code articulos}. */
    @Nonnull
    public String getClave() {
        return clave;
    }

    /** Spanish label used in the template sheet and in every row message. */
    @Nonnull
    public String getEtiqueta() {
        return etiqueta;
    }

    /**
     * Resolves the wire key (or the enum constant name) case-insensitively and
     * accent-insensitively, so {@code Artículos} and {@code ARTICULOS} both
     * land on the same target.
     *
     * @return the matching constant, or {@code null} when the key is unknown
     */
    @Nullable
    public static ImportacionMasivaEntidad desdeClave(@Nullable String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        String normalizada = normalizar(texto);
        for (ImportacionMasivaEntidad entidad : values()) {
            if (normalizar(entidad.clave).equals(normalizada)
                    || normalizar(entidad.name()).equals(normalizada)
                    || normalizar(entidad.etiqueta).equals(normalizada)) {
                return entidad;
            }
        }
        return null;
    }

    /** Accept-list for the "entidad no soportada" message. */
    @Nonnull
    public static String clavesDisponibles() {
        StringBuilder sb = new StringBuilder();
        for (ImportacionMasivaEntidad entidad : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(entidad.clave);
        }
        return sb.toString();
    }

    /**
     * Strips accents and case so {@code Artículos}/{@code articulos}/{@code
     * ARTICULOS} compare equal. Uses {@link java.text.Normalizer} rather than
     * a hardcoded replacement table, which keeps the mapping correct for the
     * whole Spanish alphabet.
     */    @Nonnull
    public static String normalizar(@Nonnull String texto) {
        return java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .trim()
                .toLowerCase(Locale.ROOT);
    }
}
