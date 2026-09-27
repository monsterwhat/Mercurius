package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * Aggregate outcome of a bulk import run: the counts the UI renders plus the
 * per-row ledger, so a caller can reconcile
 * {@code totalFilas == creados + actualizados + omitidas + rechazadas}.
 *
 * <p>Returned for BOTH modes: with {@code simulacion == true} nothing was
 * written and the counts describe what WOULD happen; with
 * {@code simulacion == false} they describe what actually happened, including
 * rows that failed at persistence time (reported as rejected, never dropped).</p>
 */
public class ImportacionMasivaResultado {

    /** Wire key of the imported target, e.g. {@code clientes}. */
    @Nonnull
    private String entidad;

    /** Spanish label, e.g. {@code Clientes}. */
    @Nonnull
    private String entidadEtiqueta;

    @Nullable
    private String archivo;

    /** True when the run was a dry run (validated, nothing persisted). */
    private boolean simulacion;

    private int totalFilas;
    private int creados;
    private int actualizados;
    private int omitidas;
    private int rechazadas;

    @Nonnull
    private String mensaje;

    /** The schema that was enforced, echoed so the UI can render it. */
    @Nonnull
    private List<ImportacionMasivaColumna> columnas = new ArrayList<>();

    /** One entry per data row read from the file — never partial. */
    @Nonnull
    private List<ImportacionMasivaFilaResultado> filas = new ArrayList<>();

    public ImportacionMasivaResultado() {
    }

    public ImportacionMasivaResultado(@Nonnull String entidad, @Nonnull String entidadEtiqueta,
                                      @Nullable String archivo, boolean simulacion,
                                      @Nonnull List<ImportacionMasivaColumna> columnas) {
        this.entidad = entidad;
        this.entidadEtiqueta = entidadEtiqueta;
        this.archivo = archivo;
        this.simulacion = simulacion;
        this.columnas = columnas;
    }

    /** Recomputes the bucket counts from {@link #filas} and the Spanish summary. */
    public void recalcular() {
        int nuevos = 0;
        int actualizados = 0;
        int omitidas = 0;
        int rechazadas = 0;
        for (ImportacionMasivaFilaResultado fila : filas) {
            switch (fila.getEstado()) {
                case ImportacionMasivaFilaResultado.NUEVO -> nuevos++;
                case ImportacionMasivaFilaResultado.ACTUALIZADO -> actualizados++;
                case ImportacionMasivaFilaResultado.OMITIDA -> omitidas++;
                default -> rechazadas++;
            }
        }
        this.creados = nuevos;
        this.actualizados = actualizados;
        this.omitidas = omitidas;
        this.rechazadas = rechazadas;
        this.totalFilas = filas.size();
        this.mensaje = construirMensaje();
    }

    private String construirMensaje() {
        StringBuilder sb = new StringBuilder();
        sb.append(simulacion ? "Simulación: " : "Importación: ");
        sb.append(creados).append(" registro(s) nuevo(s), ");
        sb.append(actualizados).append(" actualizado(s), ");
        sb.append(omitidas).append(" omitida(s), ");
        sb.append(rechazadas).append(" rechazado(s) de ").append(totalFilas).append(" fila(s).");
        if (simulacion) {
            sb.append(" No se guardó ningún cambio.");
        }
        return sb.toString();
    }

    @Nonnull
    public String getEntidad() {
        return entidad;
    }

    public void setEntidad(@Nonnull String entidad) {
        this.entidad = entidad;
    }

    @Nonnull
    public String getEntidadEtiqueta() {
        return entidadEtiqueta;
    }

    public void setEntidadEtiqueta(@Nonnull String entidadEtiqueta) {
        this.entidadEtiqueta = entidadEtiqueta;
    }

    @Nullable
    public String getArchivo() {
        return archivo;
    }

    public void setArchivo(@Nullable String archivo) {
        this.archivo = archivo;
    }

    public boolean isSimulacion() {
        return simulacion;
    }

    public void setSimulacion(boolean simulacion) {
        this.simulacion = simulacion;
    }

    public int getTotalFilas() {
        return totalFilas;
    }

    public void setTotalFilas(int totalFilas) {
        this.totalFilas = totalFilas;
    }

    public int getCreados() {
        return creados;
    }

    public void setCreados(int creados) {
        this.creados = creados;
    }

    public int getActualizados() {
        return actualizados;
    }

    public void setActualizados(int actualizados) {
        this.actualizados = actualizados;
    }

    public int getOmitidas() {
        return omitidas;
    }

    public void setOmitidas(int omitidas) {
        this.omitidas = omitidas;
    }

    public int getRechazadas() {
        return rechazadas;
    }

    public void setRechazadas(int rechazadas) {
        this.rechazadas = rechazadas;
    }

    @Nonnull
    public String getMensaje() {
        return mensaje;
    }

    public void setMensaje(@Nonnull String mensaje) {
        this.mensaje = mensaje;
    }

    @Nonnull
    public List<ImportacionMasivaColumna> getColumnas() {
        return columnas;
    }

    public void setColumnas(@Nonnull List<ImportacionMasivaColumna> columnas) {
        this.columnas = columnas;
    }

    @Nonnull
    public List<ImportacionMasivaFilaResultado> getFilas() {
        return filas;
    }

    public void setFilas(@Nonnull List<ImportacionMasivaFilaResultado> filas) {
        this.filas = filas;
    }
}
