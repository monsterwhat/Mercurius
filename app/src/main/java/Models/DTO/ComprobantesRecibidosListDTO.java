package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Lightweight comprobante recibido row for the Facturas list tables
 * (buckets Todas / Activas / Pagadas / Procesadas / Vencidas).
 * Mirrors the scalar fields of Models.ComprobantesRecibidos used by the Facturas list views
 * (Controllers.FacturasController: facturasList/facturasVencidas/getFilteredFacturasPagadas/
 * getFilteredFacturasProcesadas).
 * Relations are flattened: encabezado.clave -> clave, encabezado.numeroConsecutivo -> consecutivo,
 * encabezado.fechaEmision -> fechaEmision, encabezado.emisor.nombre -> emisorNombre,
 * encabezado.codigoDocumento -> codigoDocumento, encabezado.estado -> estado,
 * resumen.totalComprobante -> totalComprobante, resumen.totalImpuesto -> totalImpuesto.
 * Nested entities (encabezado, detalles, resumen, informacionReferencia) are intentionally excluded.
 */
public class ComprobantesRecibidosListDTO {

    private Long id; // ComprobantesRecibidos.id (rowKey)
    @Nullable private String clave; // Encabezado.clave (clave numerica de 50 digitos del comprobante)
    @Nullable private String consecutivo; // Encabezado.numeroConsecutivo
    @Nullable private LocalDateTime fechaEmision; // Encabezado.fechaEmision (bucket Vencidas)
    @Nullable private String emisorNombre; // Encabezado.emisor.nombre (columna "Emisor" del listado de facturas recibidas)
    @Nullable private BigDecimal totalComprobante; // Resumen.totalComprobante
    @Nullable private BigDecimal totalImpuesto; // Resumen.totalImpuesto
    @Nullable private String codigoDocumento; // Encabezado.codigoDocumento ("01" factura, "02" nota de credito, ...)
    @Nullable private String estado; // Encabezado.estado (estado del documento recibido)
    @Nullable private String haciendaMensajeReceptorEstado; // ComprobantesRecibidos.haciendaMensajeReceptorEstado
    @Nullable private LocalDate mensajeReceptorLimite; // ComprobantesRecibidos.mensajeReceptorLimite (bucket Vencidas de mensaje receptor)
    @Nullable private Boolean status; // ComprobantesRecibidos.status (buckets Activas/Inactivas)
    @Nullable private Boolean processed; // ComprobantesRecibidos.processed (bucket Procesadas)
    private Boolean paid; // ComprobantesRecibidos.paid (bucket Pagadas; NOT NULL default false)

    public ComprobantesRecibidosListDTO() {}

    public ComprobantesRecibidosListDTO(Long id, @Nullable String clave, @Nullable String consecutivo,
                                        @Nullable LocalDateTime fechaEmision, @Nullable String emisorNombre,
                                        @Nullable BigDecimal totalComprobante, @Nullable BigDecimal totalImpuesto,
                                        @Nullable String codigoDocumento, @Nullable String estado,
                                        @Nullable String haciendaMensajeReceptorEstado,
                                        @Nullable LocalDate mensajeReceptorLimite, @Nullable Boolean status,
                                        @Nullable Boolean processed, Boolean paid) {
        this.id = id;
        this.clave = clave;
        this.consecutivo = consecutivo;
        this.fechaEmision = fechaEmision;
        this.emisorNombre = emisorNombre;
        this.totalComprobante = totalComprobante;
        this.totalImpuesto = totalImpuesto;
        this.codigoDocumento = codigoDocumento;
        this.estado = estado;
        this.haciendaMensajeReceptorEstado = haciendaMensajeReceptorEstado;
        this.mensajeReceptorLimite = mensajeReceptorLimite;
        this.status = status;
        this.processed = processed;
        this.paid = paid;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Nullable
    public String getClave() { return clave; }
    public void setClave(@Nullable String clave) { this.clave = clave; }

    @Nullable
    public String getConsecutivo() { return consecutivo; }
    public void setConsecutivo(@Nullable String consecutivo) { this.consecutivo = consecutivo; }

    @Nullable
    public LocalDateTime getFechaEmision() { return fechaEmision; }
    public void setFechaEmision(@Nullable LocalDateTime fechaEmision) { this.fechaEmision = fechaEmision; }

    @Nullable
    public String getEmisorNombre() { return emisorNombre; }
    public void setEmisorNombre(@Nullable String emisorNombre) { this.emisorNombre = emisorNombre; }

    @Nullable
    public BigDecimal getTotalComprobante() { return totalComprobante; }
    public void setTotalComprobante(@Nullable BigDecimal totalComprobante) { this.totalComprobante = totalComprobante; }

    @Nullable
    public BigDecimal getTotalImpuesto() { return totalImpuesto; }
    public void setTotalImpuesto(@Nullable BigDecimal totalImpuesto) { this.totalImpuesto = totalImpuesto; }

    @Nullable
    public String getCodigoDocumento() { return codigoDocumento; }
    public void setCodigoDocumento(@Nullable String codigoDocumento) { this.codigoDocumento = codigoDocumento; }

    @Nullable
    public String getEstado() { return estado; }
    public void setEstado(@Nullable String estado) { this.estado = estado; }

    @Nullable
    public String getHaciendaMensajeReceptorEstado() { return haciendaMensajeReceptorEstado; }
    public void setHaciendaMensajeReceptorEstado(@Nullable String haciendaMensajeReceptorEstado) { this.haciendaMensajeReceptorEstado = haciendaMensajeReceptorEstado; }

    @Nullable
    public LocalDate getMensajeReceptorLimite() { return mensajeReceptorLimite; }
    public void setMensajeReceptorLimite(@Nullable LocalDate mensajeReceptorLimite) { this.mensajeReceptorLimite = mensajeReceptorLimite; }

    @Nullable
    public Boolean getStatus() { return status; }
    public void setStatus(@Nullable Boolean status) { this.status = status; }

    @Nullable
    public Boolean getProcessed() { return processed; }
    public void setProcessed(@Nullable Boolean processed) { this.processed = processed; }

    public Boolean getPaid() { return paid; }
    public void setPaid(Boolean paid) { this.paid = paid; }
}
