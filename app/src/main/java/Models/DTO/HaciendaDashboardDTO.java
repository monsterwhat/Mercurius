package Models.DTO;

import jakarta.annotation.Nonnull;
import java.util.Date;

/**
 * Aggregated KPI counts for the Hacienda dashboard, grouped by estado del
 * comprobante. Mirrors the counters computed by
 * {@code Controllers.HaciendaDashboardController.cargarDashboard()}:
 * ACEPTADO, RECHAZADO, and PENDIENTE (null/empty/PENDIENTE states).
 */
public class HaciendaDashboardDTO {

    /** Comprobantes con estado ACEPTADO en Hacienda. */
    private int aceptado;

    /** Comprobantes con estado RECHAZADO en Hacienda. */
    private int rechazado;

    /** Comprobantes sin estado, vacíos o PENDIENTE de envío/respuesta. */
    private int pendiente;

    /** Momento en que se calcularon estos conteos. */
    @Nonnull
    private Date lastUpdate;

    public HaciendaDashboardDTO() {
        this.lastUpdate = new Date();
    }

    public HaciendaDashboardDTO(int aceptado, int rechazado, int pendiente, @Nonnull Date lastUpdate) {
        this.aceptado = aceptado;
        this.rechazado = rechazado;
        this.pendiente = pendiente;
        this.lastUpdate = lastUpdate;
    }

    public int getAceptado() {
        return aceptado;
    }

    public void setAceptado(int aceptado) {
        this.aceptado = aceptado;
    }

    public int getRechazado() {
        return rechazado;
    }

    public void setRechazado(int rechazado) {
        this.rechazado = rechazado;
    }

    public int getPendiente() {
        return pendiente;
    }

    public void setPendiente(int pendiente) {
        this.pendiente = pendiente;
    }

    @Nonnull
    public Date getLastUpdate() {
        return lastUpdate;
    }

    public void setLastUpdate(@Nonnull Date lastUpdate) {
        this.lastUpdate = lastUpdate;
    }
}
