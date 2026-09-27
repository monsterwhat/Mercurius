package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * One column of a bulk-import template: the header cell the sheet must carry,
 * whether the row is rejected when it is missing/blank, and the Spanish hint
 * shown in the downloaded template's second sheet.
 *
 * <p>Header names intentionally reuse the captions already produced by
 * {@code Utils.ReportExporter} for the matching export dataset, so an exported
 * workbook is a valid import sheet (and vice versa) without renaming.</p>
 */
public class ImportacionMasivaColumna {

    @Nonnull
    private String encabezado;

    private boolean requerido;

    @Nullable
    private String descripcion;

    @Nullable
    private String ejemplo;

    public ImportacionMasivaColumna() {
    }

    public ImportacionMasivaColumna(@Nonnull String encabezado, boolean requerido,
                                    @Nullable String descripcion, @Nullable String ejemplo) {
        this.encabezado = encabezado;
        this.requerido = requerido;
        this.descripcion = descripcion;
        this.ejemplo = ejemplo;
    }

    @Nonnull
    public String getEncabezado() {
        return encabezado;
    }

    public void setEncabezado(@Nonnull String encabezado) {
        this.encabezado = encabezado;
    }

    public boolean isRequerido() {
        return requerido;
    }

    public void setRequerido(boolean requerido) {
        this.requerido = requerido;
    }

    @Nullable
    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(@Nullable String descripcion) {
        this.descripcion = descripcion;
    }

    @Nullable
    public String getEjemplo() {
        return ejemplo;
    }

    public void setEjemplo(@Nullable String ejemplo) {
        this.ejemplo = ejemplo;
    }
}
