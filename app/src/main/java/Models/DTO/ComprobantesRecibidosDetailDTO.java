package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Full comprobante recibido view for the Facturas detail dialog, including the
 * prevalidation-relevant fields (schemaVersion, prevalidationErrors,
 * haciendaMensajeReceptorEstado/Fecha, mensajeReceptorLimite and the paid/processed/status flags).
 * Mirrors the scalar fields of Models.ComprobantesRecibidos, Models.Encabezado.Encabezado and
 * Models.Resumen.ResumenFactura used by the Facturas detail views
 * (Controllers.FacturasController: verDetalle/prevalidar/aceptar).
 * Relations are flattened: encabezado -> scalar header fields, emisor/receptor -> nombre + identificacion
 * + correos, encabezado.medioPago -> mediosPago codes.
 * Nested entities (detalles, informacionReferencia, totalDesgloseImpuestos) are intentionally excluded.
 */
public class ComprobantesRecibidosDetailDTO {

    // ---- Identificacion y estado del comprobante (Models.ComprobantesRecibidos) ----
    private Long id; // ComprobantesRecibidos.id
    @Nullable private String schemaVersion; // ComprobantesRecibidos.schemaVersion ("4.4")
    @Nullable private Boolean status; // ComprobantesRecibidos.status (buckets Activas/Inactivas)
    @Nullable private Boolean processed; // ComprobantesRecibidos.processed (bucket Procesadas)
    private Boolean paid; // ComprobantesRecibidos.paid (bucket Pagadas; NOT NULL default false)
    @Nullable private String prevalidationErrors; // ComprobantesRecibidos.prevalidationErrors (resultado de prevalidacion)
    @Nullable private String user; // ComprobantesRecibidos.user
    @Nullable private String haciendaMensajeReceptorEstado; // ComprobantesRecibidos.haciendaMensajeReceptorEstado
    @Nullable private LocalDateTime haciendaMensajeReceptorFecha; // ComprobantesRecibidos.haciendaMensajeReceptorFecha
    @Nullable private LocalDate mensajeReceptorLimite; // ComprobantesRecibidos.mensajeReceptorLimite

    // ---- Encabezado (Models.Encabezado.Encabezado) ----
    @Nullable private String clave; // Encabezado.clave (clave numerica de 50 digitos del comprobante)
    @Nullable private String proveedorSistemas; // Encabezado.proveedorSistemas
    @Nullable private String codigoActividadEmisor; // Encabezado.codigoActividadEmisor
    @Nullable private String codigoActividadReceptor; // Encabezado.codigoActividadReceptor
    @Nullable private String consecutivo; // Encabezado.numeroConsecutivo
    @Nullable private LocalDateTime fechaEmision; // Encabezado.fechaEmision
    @Nullable private String condicionVenta; // Encabezado.condicionVenta
    @Nullable private String condicionVentaOtros; // Encabezado.condicionVentaOtros
    @Nullable private String plazoCredito; // Encabezado.plazoCredito
    @Nullable private String codigoDocumento; // Encabezado.codigoDocumento
    @Nullable private String estado; // Encabezado.estado
    @Nullable private String motivoRechazo; // Encabezado.motivoRechazo

    // ---- Emisor (Encabezado.emisor, aplanado) ----
    @Nullable private String emisorNombre; // Emisor.nombre
    @Nullable private String emisorNombreComercial; // Emisor.nombreComercial
    @Nullable private String emisorTipoIdentificacion; // Emisor.identificacion.tipo
    @Nullable private String emisorNumeroIdentificacion; // Emisor.identificacion.numero
    @Nullable private List<String> emisorCorreosElectronicos; // Emisor.correosElectronicos

    // ---- Receptor (Encabezado.receptor, aplanado) ----
    @Nullable private String receptorNombre; // Receptor.nombre
    @Nullable private String receptorNombreComercial; // Receptor.nombreComercial
    @Nullable private String receptorTipoIdentificacion; // Receptor.identificacion.tipo
    @Nullable private String receptorNumeroIdentificacion; // Receptor.identificacion.numero
    @Nullable private List<String> receptorCorreosElectronicos; // Receptor.correosElectronicos

    // ---- Medios de pago (Encabezado.medioPago: Models.Encabezado.MedioPago, aplanado a codigos) ----
    @Nullable private List<String> mediosPago; // MedioPago.medioPago (codigos de forma de pago)

    // ---- Resumen (Models.Resumen.ResumenFactura) ----
    @Nullable private String codigoMoneda; // Resumen.codigoMoneda.codigoMoneda
    @Nullable private BigDecimal tipoCambio; // Resumen.codigoMoneda.tipoCambioMoneda
    @Nullable private BigDecimal totalServGravados;
    @Nullable private BigDecimal totalServExentos;
    @Nullable private BigDecimal totalServExonerado;
    @Nullable private BigDecimal totalServNoSujeto;
    @Nullable private BigDecimal totalMercanciasGravadas;
    @Nullable private BigDecimal totalMercanciasExentas;
    @Nullable private BigDecimal totalMercExonerada;
    @Nullable private BigDecimal totalMercNoSujeta;
    @Nullable private BigDecimal totalGravado;
    @Nullable private BigDecimal totalExento;
    @Nullable private BigDecimal totalExonerado;
    @Nullable private BigDecimal totalNoSujeto;
    @Nullable private BigDecimal totalVenta;
    @Nullable private BigDecimal totalDescuentos;
    @Nullable private BigDecimal totalVentaNeta;
    @Nullable private BigDecimal totalImpuesto;
    @Nullable private BigDecimal totalImpuestoAsumidoEmisorFabrica;
    @Nullable private BigDecimal totalIVADevuelto;
    @Nullable private BigDecimal totalOtrosCargos;
    @Nullable private BigDecimal totalComprobante;

    public ComprobantesRecibidosDetailDTO() {}

    public ComprobantesRecibidosDetailDTO(Long id, @Nullable String schemaVersion,
                                          @Nullable Boolean status, @Nullable Boolean processed,
                                          Boolean paid, @Nullable String prevalidationErrors,
                                          @Nullable String user,
                                          @Nullable String haciendaMensajeReceptorEstado,
                                          @Nullable LocalDateTime haciendaMensajeReceptorFecha,
                                          @Nullable LocalDate mensajeReceptorLimite,
                                          @Nullable String clave, @Nullable String proveedorSistemas,
                                          @Nullable String codigoActividadEmisor,
                                          @Nullable String codigoActividadReceptor,
                                          @Nullable String consecutivo, @Nullable LocalDateTime fechaEmision,
                                          @Nullable String condicionVenta, @Nullable String condicionVentaOtros,
                                          @Nullable String plazoCredito, @Nullable String codigoDocumento,
                                          @Nullable String estado, @Nullable String motivoRechazo,
                                          @Nullable String emisorNombre, @Nullable String emisorNombreComercial,
                                          @Nullable String emisorTipoIdentificacion,
                                          @Nullable String emisorNumeroIdentificacion,
                                          @Nullable List<String> emisorCorreosElectronicos,
                                          @Nullable String receptorNombre, @Nullable String receptorNombreComercial,
                                          @Nullable String receptorTipoIdentificacion,
                                          @Nullable String receptorNumeroIdentificacion,
                                          @Nullable List<String> receptorCorreosElectronicos,
                                          @Nullable List<String> mediosPago, @Nullable String codigoMoneda,
                                          @Nullable BigDecimal tipoCambio, @Nullable BigDecimal totalServGravados,
                                          @Nullable BigDecimal totalServExentos, @Nullable BigDecimal totalServExonerado,
                                          @Nullable BigDecimal totalServNoSujeto,
                                          @Nullable BigDecimal totalMercanciasGravadas,
                                          @Nullable BigDecimal totalMercanciasExentas,
                                          @Nullable BigDecimal totalMercExonerada,
                                          @Nullable BigDecimal totalMercNoSujeta, @Nullable BigDecimal totalGravado,
                                          @Nullable BigDecimal totalExento, @Nullable BigDecimal totalExonerado,
                                          @Nullable BigDecimal totalNoSujeto, @Nullable BigDecimal totalVenta,
                                          @Nullable BigDecimal totalDescuentos, @Nullable BigDecimal totalVentaNeta,
                                          @Nullable BigDecimal totalImpuesto,
                                          @Nullable BigDecimal totalImpuestoAsumidoEmisorFabrica,
                                          @Nullable BigDecimal totalIVADevuelto, @Nullable BigDecimal totalOtrosCargos,
                                          @Nullable BigDecimal totalComprobante) {
        this.id = id;
        this.schemaVersion = schemaVersion;
        this.status = status;
        this.processed = processed;
        this.paid = paid;
        this.prevalidationErrors = prevalidationErrors;
        this.user = user;
        this.haciendaMensajeReceptorEstado = haciendaMensajeReceptorEstado;
        this.haciendaMensajeReceptorFecha = haciendaMensajeReceptorFecha;
        this.mensajeReceptorLimite = mensajeReceptorLimite;
        this.clave = clave;
        this.proveedorSistemas = proveedorSistemas;
        this.codigoActividadEmisor = codigoActividadEmisor;
        this.codigoActividadReceptor = codigoActividadReceptor;
        this.consecutivo = consecutivo;
        this.fechaEmision = fechaEmision;
        this.condicionVenta = condicionVenta;
        this.condicionVentaOtros = condicionVentaOtros;
        this.plazoCredito = plazoCredito;
        this.codigoDocumento = codigoDocumento;
        this.estado = estado;
        this.motivoRechazo = motivoRechazo;
        this.emisorNombre = emisorNombre;
        this.emisorNombreComercial = emisorNombreComercial;
        this.emisorTipoIdentificacion = emisorTipoIdentificacion;
        this.emisorNumeroIdentificacion = emisorNumeroIdentificacion;
        this.emisorCorreosElectronicos = emisorCorreosElectronicos;
        this.receptorNombre = receptorNombre;
        this.receptorNombreComercial = receptorNombreComercial;
        this.receptorTipoIdentificacion = receptorTipoIdentificacion;
        this.receptorNumeroIdentificacion = receptorNumeroIdentificacion;
        this.receptorCorreosElectronicos = receptorCorreosElectronicos;
        this.mediosPago = mediosPago;
        this.codigoMoneda = codigoMoneda;
        this.tipoCambio = tipoCambio;
        this.totalServGravados = totalServGravados;
        this.totalServExentos = totalServExentos;
        this.totalServExonerado = totalServExonerado;
        this.totalServNoSujeto = totalServNoSujeto;
        this.totalMercanciasGravadas = totalMercanciasGravadas;
        this.totalMercanciasExentas = totalMercanciasExentas;
        this.totalMercExonerada = totalMercExonerada;
        this.totalMercNoSujeta = totalMercNoSujeta;
        this.totalGravado = totalGravado;
        this.totalExento = totalExento;
        this.totalExonerado = totalExonerado;
        this.totalNoSujeto = totalNoSujeto;
        this.totalVenta = totalVenta;
        this.totalDescuentos = totalDescuentos;
        this.totalVentaNeta = totalVentaNeta;
        this.totalImpuesto = totalImpuesto;
        this.totalImpuestoAsumidoEmisorFabrica = totalImpuestoAsumidoEmisorFabrica;
        this.totalIVADevuelto = totalIVADevuelto;
        this.totalOtrosCargos = totalOtrosCargos;
        this.totalComprobante = totalComprobante;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Nullable
    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(@Nullable String schemaVersion) { this.schemaVersion = schemaVersion; }

    @Nullable
    public Boolean getStatus() { return status; }
    public void setStatus(@Nullable Boolean status) { this.status = status; }

    @Nullable
    public Boolean getProcessed() { return processed; }
    public void setProcessed(@Nullable Boolean processed) { this.processed = processed; }

    public Boolean getPaid() { return paid; }
    public void setPaid(Boolean paid) { this.paid = paid; }

    @Nullable
    public String getPrevalidationErrors() { return prevalidationErrors; }
    public void setPrevalidationErrors(@Nullable String prevalidationErrors) { this.prevalidationErrors = prevalidationErrors; }

    @Nullable
    public String getUser() { return user; }
    public void setUser(@Nullable String user) { this.user = user; }

    @Nullable
    public String getHaciendaMensajeReceptorEstado() { return haciendaMensajeReceptorEstado; }
    public void setHaciendaMensajeReceptorEstado(@Nullable String haciendaMensajeReceptorEstado) { this.haciendaMensajeReceptorEstado = haciendaMensajeReceptorEstado; }

    @Nullable
    public LocalDateTime getHaciendaMensajeReceptorFecha() { return haciendaMensajeReceptorFecha; }
    public void setHaciendaMensajeReceptorFecha(@Nullable LocalDateTime haciendaMensajeReceptorFecha) { this.haciendaMensajeReceptorFecha = haciendaMensajeReceptorFecha; }

    @Nullable
    public LocalDate getMensajeReceptorLimite() { return mensajeReceptorLimite; }
    public void setMensajeReceptorLimite(@Nullable LocalDate mensajeReceptorLimite) { this.mensajeReceptorLimite = mensajeReceptorLimite; }

    @Nullable
    public String getClave() { return clave; }
    public void setClave(@Nullable String clave) { this.clave = clave; }

    @Nullable
    public String getProveedorSistemas() { return proveedorSistemas; }
    public void setProveedorSistemas(@Nullable String proveedorSistemas) { this.proveedorSistemas = proveedorSistemas; }

    @Nullable
    public String getCodigoActividadEmisor() { return codigoActividadEmisor; }
    public void setCodigoActividadEmisor(@Nullable String codigoActividadEmisor) { this.codigoActividadEmisor = codigoActividadEmisor; }

    @Nullable
    public String getCodigoActividadReceptor() { return codigoActividadReceptor; }
    public void setCodigoActividadReceptor(@Nullable String codigoActividadReceptor) { this.codigoActividadReceptor = codigoActividadReceptor; }

    @Nullable
    public String getConsecutivo() { return consecutivo; }
    public void setConsecutivo(@Nullable String consecutivo) { this.consecutivo = consecutivo; }

    @Nullable
    public LocalDateTime getFechaEmision() { return fechaEmision; }
    public void setFechaEmision(@Nullable LocalDateTime fechaEmision) { this.fechaEmision = fechaEmision; }

    @Nullable
    public String getCondicionVenta() { return condicionVenta; }
    public void setCondicionVenta(@Nullable String condicionVenta) { this.condicionVenta = condicionVenta; }

    @Nullable
    public String getCondicionVentaOtros() { return condicionVentaOtros; }
    public void setCondicionVentaOtros(@Nullable String condicionVentaOtros) { this.condicionVentaOtros = condicionVentaOtros; }

    @Nullable
    public String getPlazoCredito() { return plazoCredito; }
    public void setPlazoCredito(@Nullable String plazoCredito) { this.plazoCredito = plazoCredito; }

    @Nullable
    public String getCodigoDocumento() { return codigoDocumento; }
    public void setCodigoDocumento(@Nullable String codigoDocumento) { this.codigoDocumento = codigoDocumento; }

    @Nullable
    public String getEstado() { return estado; }
    public void setEstado(@Nullable String estado) { this.estado = estado; }

    @Nullable
    public String getMotivoRechazo() { return motivoRechazo; }
    public void setMotivoRechazo(@Nullable String motivoRechazo) { this.motivoRechazo = motivoRechazo; }

    @Nullable
    public String getEmisorNombre() { return emisorNombre; }
    public void setEmisorNombre(@Nullable String emisorNombre) { this.emisorNombre = emisorNombre; }

    @Nullable
    public String getEmisorNombreComercial() { return emisorNombreComercial; }
    public void setEmisorNombreComercial(@Nullable String emisorNombreComercial) { this.emisorNombreComercial = emisorNombreComercial; }

    @Nullable
    public String getEmisorTipoIdentificacion() { return emisorTipoIdentificacion; }
    public void setEmisorTipoIdentificacion(@Nullable String emisorTipoIdentificacion) { this.emisorTipoIdentificacion = emisorTipoIdentificacion; }

    @Nullable
    public String getEmisorNumeroIdentificacion() { return emisorNumeroIdentificacion; }
    public void setEmisorNumeroIdentificacion(@Nullable String emisorNumeroIdentificacion) { this.emisorNumeroIdentificacion = emisorNumeroIdentificacion; }

    @Nullable
    public List<String> getEmisorCorreosElectronicos() { return emisorCorreosElectronicos; }
    public void setEmisorCorreosElectronicos(@Nullable List<String> emisorCorreosElectronicos) { this.emisorCorreosElectronicos = emisorCorreosElectronicos; }

    @Nullable
    public String getReceptorNombre() { return receptorNombre; }
    public void setReceptorNombre(@Nullable String receptorNombre) { this.receptorNombre = receptorNombre; }

    @Nullable
    public String getReceptorNombreComercial() { return receptorNombreComercial; }
    public void setReceptorNombreComercial(@Nullable String receptorNombreComercial) { this.receptorNombreComercial = receptorNombreComercial; }

    @Nullable
    public String getReceptorTipoIdentificacion() { return receptorTipoIdentificacion; }
    public void setReceptorTipoIdentificacion(@Nullable String receptorTipoIdentificacion) { this.receptorTipoIdentificacion = receptorTipoIdentificacion; }

    @Nullable
    public String getReceptorNumeroIdentificacion() { return receptorNumeroIdentificacion; }
    public void setReceptorNumeroIdentificacion(@Nullable String receptorNumeroIdentificacion) { this.receptorNumeroIdentificacion = receptorNumeroIdentificacion; }

    @Nullable
    public List<String> getReceptorCorreosElectronicos() { return receptorCorreosElectronicos; }
    public void setReceptorCorreosElectronicos(@Nullable List<String> receptorCorreosElectronicos) { this.receptorCorreosElectronicos = receptorCorreosElectronicos; }

    @Nullable
    public List<String> getMediosPago() { return mediosPago; }
    public void setMediosPago(@Nullable List<String> mediosPago) { this.mediosPago = mediosPago; }

    @Nullable
    public String getCodigoMoneda() { return codigoMoneda; }
    public void setCodigoMoneda(@Nullable String codigoMoneda) { this.codigoMoneda = codigoMoneda; }

    @Nullable
    public BigDecimal getTipoCambio() { return tipoCambio; }
    public void setTipoCambio(@Nullable BigDecimal tipoCambio) { this.tipoCambio = tipoCambio; }

    @Nullable
    public BigDecimal getTotalServGravados() { return totalServGravados; }
    public void setTotalServGravados(@Nullable BigDecimal totalServGravados) { this.totalServGravados = totalServGravados; }

    @Nullable
    public BigDecimal getTotalServExentos() { return totalServExentos; }
    public void setTotalServExentos(@Nullable BigDecimal totalServExentos) { this.totalServExentos = totalServExentos; }

    @Nullable
    public BigDecimal getTotalServExonerado() { return totalServExonerado; }
    public void setTotalServExonerado(@Nullable BigDecimal totalServExonerado) { this.totalServExonerado = totalServExonerado; }

    @Nullable
    public BigDecimal getTotalServNoSujeto() { return totalServNoSujeto; }
    public void setTotalServNoSujeto(@Nullable BigDecimal totalServNoSujeto) { this.totalServNoSujeto = totalServNoSujeto; }

    @Nullable
    public BigDecimal getTotalMercanciasGravadas() { return totalMercanciasGravadas; }
    public void setTotalMercanciasGravadas(@Nullable BigDecimal totalMercanciasGravadas) { this.totalMercanciasGravadas = totalMercanciasGravadas; }

    @Nullable
    public BigDecimal getTotalMercanciasExentas() { return totalMercanciasExentas; }
    public void setTotalMercanciasExentas(@Nullable BigDecimal totalMercanciasExentas) { this.totalMercanciasExentas = totalMercanciasExentas; }

    @Nullable
    public BigDecimal getTotalMercExonerada() { return totalMercExonerada; }
    public void setTotalMercExonerada(@Nullable BigDecimal totalMercExonerada) { this.totalMercExonerada = totalMercExonerada; }

    @Nullable
    public BigDecimal getTotalMercNoSujeta() { return totalMercNoSujeta; }
    public void setTotalMercNoSujeta(@Nullable BigDecimal totalMercNoSujeta) { this.totalMercNoSujeta = totalMercNoSujeta; }

    @Nullable
    public BigDecimal getTotalGravado() { return totalGravado; }
    public void setTotalGravado(@Nullable BigDecimal totalGravado) { this.totalGravado = totalGravado; }

    @Nullable
    public BigDecimal getTotalExento() { return totalExento; }
    public void setTotalExento(@Nullable BigDecimal totalExento) { this.totalExento = totalExento; }

    @Nullable
    public BigDecimal getTotalExonerado() { return totalExonerado; }
    public void setTotalExonerado(@Nullable BigDecimal totalExonerado) { this.totalExonerado = totalExonerado; }

    @Nullable
    public BigDecimal getTotalNoSujeto() { return totalNoSujeto; }
    public void setTotalNoSujeto(@Nullable BigDecimal totalNoSujeto) { this.totalNoSujeto = totalNoSujeto; }

    @Nullable
    public BigDecimal getTotalVenta() { return totalVenta; }
    public void setTotalVenta(@Nullable BigDecimal totalVenta) { this.totalVenta = totalVenta; }

    @Nullable
    public BigDecimal getTotalDescuentos() { return totalDescuentos; }
    public void setTotalDescuentos(@Nullable BigDecimal totalDescuentos) { this.totalDescuentos = totalDescuentos; }

    @Nullable
    public BigDecimal getTotalVentaNeta() { return totalVentaNeta; }
    public void setTotalVentaNeta(@Nullable BigDecimal totalVentaNeta) { this.totalVentaNeta = totalVentaNeta; }

    @Nullable
    public BigDecimal getTotalImpuesto() { return totalImpuesto; }
    public void setTotalImpuesto(@Nullable BigDecimal totalImpuesto) { this.totalImpuesto = totalImpuesto; }

    @Nullable
    public BigDecimal getTotalImpuestoAsumidoEmisorFabrica() { return totalImpuestoAsumidoEmisorFabrica; }
    public void setTotalImpuestoAsumidoEmisorFabrica(@Nullable BigDecimal totalImpuestoAsumidoEmisorFabrica) { this.totalImpuestoAsumidoEmisorFabrica = totalImpuestoAsumidoEmisorFabrica; }

    @Nullable
    public BigDecimal getTotalIVADevuelto() { return totalIVADevuelto; }
    public void setTotalIVADevuelto(@Nullable BigDecimal totalIVADevuelto) { this.totalIVADevuelto = totalIVADevuelto; }

    @Nullable
    public BigDecimal getTotalOtrosCargos() { return totalOtrosCargos; }
    public void setTotalOtrosCargos(@Nullable BigDecimal totalOtrosCargos) { this.totalOtrosCargos = totalOtrosCargos; }

    @Nullable
    public BigDecimal getTotalComprobante() { return totalComprobante; }
    public void setTotalComprobante(@Nullable BigDecimal totalComprobante) { this.totalComprobante = totalComprobante; }
}
