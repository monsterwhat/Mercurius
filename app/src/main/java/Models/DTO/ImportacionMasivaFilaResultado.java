package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of ONE spreadsheet row. Every data row read from the uploaded file
 * produces exactly one of these — including fully blank rows and rows rejected
 * by validation — so a caller can always reconcile {@code totalFilas} against
 * the sum of its buckets and no row is ever silently dropped.
 */
public class ImportacionMasivaFilaResultado {

    /** Row that will be INSERTED. */
    public static final String NUEVO = "NUEVO";
    /** Row that will UPDATE an existing record (natural key already present). */
    public static final String ACTUALIZADO = "ACTUALIZADO";
    /** Row intentionally not imported (e.g. a fully blank separator row). */
    public static final String OMITIDA = "OMITIDA";
    /** Row rejected by schema/business validation; {@link #motivo} explains why. */
    public static final String RECHAZADA = "RECHAZADA";

    /** 1-based spreadsheet row number (row 1 is the header), for user-facing errors. */
    private int fila;

    /** Natural key the row resolved to (cédula, código de barras, código CABYS…). */
    @Nullable
    private String clave;

    @Nonnull
    private String estado;

    private boolean aceptado;

    /** Spanish explanation. Always populated: acceptance reason or rejection reason. */
    @Nullable
    private String motivo;

    /** Non-blocking remarks (e.g. computed precio final, ignored unknown column). */
    @Nonnull
    private List<String> avisos = new ArrayList<>();

    public ImportacionMasivaFilaResultado() {
    }

    public ImportacionMasivaFilaResultado(int fila, @Nullable String clave, @Nonnull String estado,
                                          boolean aceptado, @Nullable String motivo) {
        this.fila = fila;
        this.clave = clave;
        this.estado = estado;
        this.aceptado = aceptado;
        this.motivo = motivo;
    }

    /** Accepted row that will be inserted. */
    @Nonnull
    public static ImportacionMasivaFilaResultado nuevo(int fila, @Nonnull String clave, @Nonnull String motivo) {
        return new ImportacionMasivaFilaResultado(fila, clave, NUEVO, true, motivo);
    }

    /** Accepted row that will update an existing record. */
    @Nonnull
    public static ImportacionMasivaFilaResultado actualizado(int fila, @Nonnull String clave, @Nonnull String motivo) {
        return new ImportacionMasivaFilaResultado(fila, clave, ACTUALIZADO, true, motivo);
    }

    /** Rejected row; {@code motivo} is mandatory here. */
    @Nonnull
    public static ImportacionMasivaFilaResultado rechazada(int fila, @Nullable String clave, @Nonnull String motivo) {
        return new ImportacionMasivaFilaResultado(fila, clave, RECHAZADA, false, motivo);
    }

    /** Skipped row (blank separator row or a key already seen in this file). */
    @Nonnull
    public static ImportacionMasivaFilaResultado omitida(int fila, @Nullable String clave, @Nonnull String motivo) {
        return new ImportacionMasivaFilaResultado(fila, clave, OMITIDA, false, motivo);
    }

    @Nonnull
    public ImportacionMasivaFilaResultado conAviso(@Nullable String aviso) {
        if (aviso != null && !aviso.isBlank()) {
            avisos.add(aviso);
        }
        return this;
    }

    public int getFila() {
        return fila;
    }

    public void setFila(int fila) {
        this.fila = fila;
    }

    @Nullable
    public String getClave() {
        return clave;
    }

    public void setClave(@Nullable String clave) {
        this.clave = clave;
    }

    @Nonnull
    public String getEstado() {
        return estado;
    }

    public void setEstado(@Nonnull String estado) {
        this.estado = estado;
    }

    public boolean isAceptado() {
        return aceptado;
    }

    public void setAceptado(boolean aceptado) {
        this.aceptado = aceptado;
    }

    @Nullable
    public String getMotivo() {
        return motivo;
    }

    public void setMotivo(@Nullable String motivo) {
        this.motivo = motivo;
    }

    @Nonnull
    public List<String> getAvisos() {
        return avisos;
    }

    public void setAvisos(@Nonnull List<String> avisos) {
        this.avisos = avisos;
    }
}
