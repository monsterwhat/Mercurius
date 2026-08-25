package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Lightweight comprobante row for the Recibos list tables (buckets Todas / Pagadas / Procesadas / Vencidas).
 * Mirrors the scalar fields of Models.ComprobantesEmitidos used by the Recibos list views
 * (META-INF/resources/secured/pages/Recibos/index.xhtml).
 * Relations are flattened: encabezado.numeroConsecutivo -> consecutivo, encabezado.fechaEmision -> fechaEmision,
 * encabezado.emisor.nombre -> emisorNombre, encabezado.receptor.nombre -> receptorNombre,
 * resumen.totalComprobante -> totalComprobante, resumen.totalImpuesto -> totalImpuesto.
 * Nested entities (encabezado, detalles, resumen, informacionReferencia) are intentionally excluded.
 */
public class ComprobantesEmitidosListDTO {

    private Long id; // ComprobantesEmitidos.id (rowKey)
    @Nullable private String consecutivo; // Encabezado.numeroConsecutivo
    @Nullable private LocalDateTime fechaEmision; // Encabezado.fechaEmision
    @Nullable private String emisorNombre; // Encabezado.emisor.nombre (columna "Emisor" del listado de recibos)
    @Nullable private String receptorNombre; // Encabezado.receptor.nombre (cliente del comprobante emitido)
    @Nullable private BigDecimal totalComprobante; // Resumen.totalComprobante
    @Nullable private BigDecimal totalImpuesto; // Resumen.totalImpuesto
    @Nullable private String condicionVenta; // Encabezado.condicionVenta (tab Pagadas)
    @Nullable private String plazoCredito; // Encabezado.plazoCredito (tabs Pagadas y Vencidas)
    @Nullable private String codigoDocumento; // Encabezado.codigoDocumento ("01" factura, "02" nota de credito, ...)
    @Nullable private String haciendaEstado; // ComprobantesEmitidos.haciendaEstado (PENDIENTE, ACEPTADO, RECHAZADO, ...)
    @Nullable private Boolean status; // ComprobantesEmitidos.status (chip activo/inactivo)

    public ComprobantesEmitidosListDTO() {}

    public ComprobantesEmitidosListDTO(Long id, @Nullable String consecutivo, @Nullable LocalDateTime fechaEmision,
                                       @Nullable String emisorNombre, @Nullable String receptorNombre,
                                       @Nullable BigDecimal totalComprobante, @Nullable BigDecimal totalImpuesto,
                                       @Nullable String condicionVenta, @Nullable String plazoCredito,
                                       @Nullable String codigoDocumento, @Nullable String haciendaEstado,
                                       @Nullable Boolean status) {
        this.id = id;
        this.consecutivo = consecutivo;
        this.fechaEmision = fechaEmision;
        this.emisorNombre = emisorNombre;
        this.receptorNombre = receptorNombre;
        this.totalComprobante = totalComprobante;
        this.totalImpuesto = totalImpuesto;
        this.condicionVenta = condicionVenta;
        this.plazoCredito = plazoCredito;
        this.codigoDocumento = codigoDocumento;
        this.haciendaEstado = haciendaEstado;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

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
    public String getReceptorNombre() { return receptorNombre; }
    public void setReceptorNombre(@Nullable String receptorNombre) { this.receptorNombre = receptorNombre; }

    @Nullable
    public BigDecimal getTotalComprobante() { return totalComprobante; }
    public void setTotalComprobante(@Nullable BigDecimal totalComprobante) { this.totalComprobante = totalComprobante; }

    @Nullable
    public BigDecimal getTotalImpuesto() { return totalImpuesto; }
    public void setTotalImpuesto(@Nullable BigDecimal totalImpuesto) { this.totalImpuesto = totalImpuesto; }

    @Nullable
    public String getCondicionVenta() { return condicionVenta; }
    public void setCondicionVenta(@Nullable String condicionVenta) { this.condicionVenta = condicionVenta; }

    @Nullable
    public String getPlazoCredito() { return plazoCredito; }
    public void setPlazoCredito(@Nullable String plazoCredito) { this.plazoCredito = plazoCredito; }

    @Nullable
    public String getCodigoDocumento() { return codigoDocumento; }
    public void setCodigoDocumento(@Nullable String codigoDocumento) { this.codigoDocumento = codigoDocumento; }

    @Nullable
    public String getHaciendaEstado() { return haciendaEstado; }
    public void setHaciendaEstado(@Nullable String haciendaEstado) { this.haciendaEstado = haciendaEstado; }

    @Nullable
    public Boolean getStatus() { return status; }
    public void setStatus(@Nullable Boolean status) { this.status = status; }
}
