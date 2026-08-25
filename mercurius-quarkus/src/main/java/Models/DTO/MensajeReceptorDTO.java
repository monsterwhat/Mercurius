package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Read-side view of the Mensaje Receptor (mensaje de recepción) that Mercurius
 * sends to Hacienda for each comprobante recibido.
 *
 * NOTE: there is no dedicated {@code Models.Registros.MensajeReceptor} entity
 * in this codebase; the MR state lives on {@code Models.ComprobantesRecibidos}
 * ({@code haciendaMensajeReceptorEstado}, {@code haciendaMensajeReceptorFecha},
 * {@code mensajeReceptorLimite}). This DTO flattens those fields together with
 * the invoice identification ({@code Encabezado}) and monetary totals
 * ({@code ResumenFactura}), as displayed by the Tributacion pages.
 */
public class MensajeReceptorDTO {

    /** Consecutivo del comprobante recibido (encabezado.numeroConsecutivo). */
    @Nullable
    private String consecutivo;

    /** Clave única del comprobante ante Hacienda (encabezado.clave). */
    @Nullable
    private String clave;

    /** Fecha de emisión del comprobante original (encabezado.fechaEmision). */
    @Nullable
    private LocalDateTime fechaEmision;

    /**
     * Estado reportado por el Mensaje Receptor
     * (haciendaMensajeReceptorEstado): ACEPTADO, ACEPTADO PARCIAL, RECHAZADO.
     */
    @Nullable
    private String estado;

    /** Fecha de envío/respuesta del Mensaje Receptor (haciendaMensajeReceptorFecha). */
    @Nullable
    private LocalDateTime fecha;

    /** Límite de 8 días hábiles para enviar el MR (mensajeReceptorLimite). */
    @Nullable
    private LocalDate limite;

    /** Monto total del impuesto declarado en el MR (resumen.totalImpuesto). */
    @Nullable
    private BigDecimal montoTotalImpuesto;

    /** Monto total de la factura declarado en el MR (resumen.totalComprobante). */
    @Nullable
    private BigDecimal montoTotalFactura;

    /** Nombre del emisor de la factura original (encabezado.emisor.nombre). */
    @Nullable
    private String emisorNombre;

    /** Número de identificación del emisor (encabezado.emisor.identificacion.numero). */
    @Nullable
    private String emisorIdentificacion;

    /** Nombre del receptor de la factura original (encabezado.receptor.nombre). */
    @Nullable
    private String receptorNombre;

    /** Número de identificación del receptor (encabezado.receptor.identificacion.numero). */
    @Nullable
    private String receptorIdentificacion;

    public MensajeReceptorDTO() {
    }

    public MensajeReceptorDTO(@Nullable String consecutivo, @Nullable String clave,
                              @Nullable LocalDateTime fechaEmision, @Nullable String estado,
                              @Nullable LocalDateTime fecha, @Nullable LocalDate limite,
                              @Nullable BigDecimal montoTotalImpuesto, @Nullable BigDecimal montoTotalFactura,
                              @Nullable String emisorNombre, @Nullable String emisorIdentificacion,
                              @Nullable String receptorNombre, @Nullable String receptorIdentificacion) {
        this.consecutivo = consecutivo;
        this.clave = clave;
        this.fechaEmision = fechaEmision;
        this.estado = estado;
        this.fecha = fecha;
        this.limite = limite;
        this.montoTotalImpuesto = montoTotalImpuesto;
        this.montoTotalFactura = montoTotalFactura;
        this.emisorNombre = emisorNombre;
        this.emisorIdentificacion = emisorIdentificacion;
        this.receptorNombre = receptorNombre;
        this.receptorIdentificacion = receptorIdentificacion;
    }

    @Nullable
    public String getConsecutivo() {
        return consecutivo;
    }

    public void setConsecutivo(@Nullable String consecutivo) {
        this.consecutivo = consecutivo;
    }

    @Nullable
    public String getClave() {
        return clave;
    }

    public void setClave(@Nullable String clave) {
        this.clave = clave;
    }

    @Nullable
    public LocalDateTime getFechaEmision() {
        return fechaEmision;
    }

    public void setFechaEmision(@Nullable LocalDateTime fechaEmision) {
        this.fechaEmision = fechaEmision;
    }

    @Nullable
    public String getEstado() {
        return estado;
    }

    public void setEstado(@Nullable String estado) {
        this.estado = estado;
    }

    @Nullable
    public LocalDateTime getFecha() {
        return fecha;
    }

    public void setFecha(@Nullable LocalDateTime fecha) {
        this.fecha = fecha;
    }

    @Nullable
    public LocalDate getLimite() {
        return limite;
    }

    public void setLimite(@Nullable LocalDate limite) {
        this.limite = limite;
    }

    @Nullable
    public BigDecimal getMontoTotalImpuesto() {
        return montoTotalImpuesto;
    }

    public void setMontoTotalImpuesto(@Nullable BigDecimal montoTotalImpuesto) {
        this.montoTotalImpuesto = montoTotalImpuesto;
    }

    @Nullable
    public BigDecimal getMontoTotalFactura() {
        return montoTotalFactura;
    }

    public void setMontoTotalFactura(@Nullable BigDecimal montoTotalFactura) {
        this.montoTotalFactura = montoTotalFactura;
    }

    @Nullable
    public String getEmisorNombre() {
        return emisorNombre;
    }

    public void setEmisorNombre(@Nullable String emisorNombre) {
        this.emisorNombre = emisorNombre;
    }

    @Nullable
    public String getEmisorIdentificacion() {
        return emisorIdentificacion;
    }

    public void setEmisorIdentificacion(@Nullable String emisorIdentificacion) {
        this.emisorIdentificacion = emisorIdentificacion;
    }

    @Nullable
    public String getReceptorNombre() {
        return receptorNombre;
    }

    public void setReceptorNombre(@Nullable String receptorNombre) {
        this.receptorNombre = receptorNombre;
    }

    @Nullable
    public String getReceptorIdentificacion() {
        return receptorIdentificacion;
    }

    public void setReceptorIdentificacion(@Nullable String receptorIdentificacion) {
        this.receptorIdentificacion = receptorIdentificacion;
    }
}
