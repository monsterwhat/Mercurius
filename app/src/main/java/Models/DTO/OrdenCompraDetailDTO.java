package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * DTO de detalle para Órdenes de Compra.
 * Espejo plano de Models.OrdenCompra con todas sus líneas aplanadas
 * (Models.DTO.OrdenCompraLineaDTO) y los campos del flujo de estados
 * (BORRADOR → ENVIADA → CONFIRMADA → RECIBIDA → FACTURADA / CANCELADA):
 * estado, fechas de entrega estimada/real y totales estimado/real.
 * Cubre los formularios crear/editar/detalles/estado/cancelar de
 * secured/pages/Compras/Ordenes/index.xhtml. Relaciones aplanadas, sin entidades.
 */
public class OrdenCompraDetailDTO {
    private Long id;
    private String numeroOrden;
    @Nullable private Integer proveedorId;
    @Nullable private String proveedorNombre;
    private Date fechaOrden;
    @Nullable private Date fechaEntregaEstimada;
    @Nullable private Date fechaEntregaReal;
    private String estado; // BORRADOR, ENVIADA, CONFIRMADA, RECIBIDA, FACTURADA, CANCELADA
    @Nullable private BigDecimal totalEstimado;
    @Nullable private BigDecimal totalReal;
    @Nullable private String notas;
    @Nullable private Long usuarioId;
    @Nullable private String usuarioUsername;
    private Date fecha;
    private boolean status; // true = activo, false = eliminado lógico
    @Nullable private List<OrdenCompraLineaDTO> detalles;

    public OrdenCompraDetailDTO() {}

    public OrdenCompraDetailDTO(Long id, String numeroOrden, Integer proveedorId, String proveedorNombre,
                                Date fechaOrden, Date fechaEntregaEstimada, Date fechaEntregaReal,
                                String estado, BigDecimal totalEstimado, BigDecimal totalReal, String notas,
                                Long usuarioId, String usuarioUsername, Date fecha, boolean status,
                                List<OrdenCompraLineaDTO> detalles) {
        this.id = id;
        this.numeroOrden = numeroOrden;
        this.proveedorId = proveedorId;
        this.proveedorNombre = proveedorNombre;
        this.fechaOrden = fechaOrden;
        this.fechaEntregaEstimada = fechaEntregaEstimada;
        this.fechaEntregaReal = fechaEntregaReal;
        this.estado = estado;
        this.totalEstimado = totalEstimado;
        this.totalReal = totalReal;
        this.notas = notas;
        this.usuarioId = usuarioId;
        this.usuarioUsername = usuarioUsername;
        this.fecha = fecha;
        this.status = status;
        this.detalles = detalles;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getNumeroOrden() { return numeroOrden; }
    public void setNumeroOrden(String numeroOrden) { this.numeroOrden = numeroOrden; }

    @Nullable
    public Integer getProveedorId() { return proveedorId; }
    public void setProveedorId(@Nullable Integer proveedorId) { this.proveedorId = proveedorId; }

    @Nullable
    public String getProveedorNombre() { return proveedorNombre; }
    public void setProveedorNombre(@Nullable String proveedorNombre) { this.proveedorNombre = proveedorNombre; }

    public Date getFechaOrden() { return fechaOrden; }
    public void setFechaOrden(Date fechaOrden) { this.fechaOrden = fechaOrden; }

    @Nullable
    public Date getFechaEntregaEstimada() { return fechaEntregaEstimada; }
    public void setFechaEntregaEstimada(@Nullable Date fechaEntregaEstimada) { this.fechaEntregaEstimada = fechaEntregaEstimada; }

    @Nullable
    public Date getFechaEntregaReal() { return fechaEntregaReal; }
    public void setFechaEntregaReal(@Nullable Date fechaEntregaReal) { this.fechaEntregaReal = fechaEntregaReal; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    @Nullable
    public BigDecimal getTotalEstimado() { return totalEstimado; }
    public void setTotalEstimado(@Nullable BigDecimal totalEstimado) { this.totalEstimado = totalEstimado; }

    @Nullable
    public BigDecimal getTotalReal() { return totalReal; }
    public void setTotalReal(@Nullable BigDecimal totalReal) { this.totalReal = totalReal; }

    @Nullable
    public String getNotas() { return notas; }
    public void setNotas(@Nullable String notas) { this.notas = notas; }

    @Nullable
    public Long getUsuarioId() { return usuarioId; }
    public void setUsuarioId(@Nullable Long usuarioId) { this.usuarioId = usuarioId; }

    @Nullable
    public String getUsuarioUsername() { return usuarioUsername; }
    public void setUsuarioUsername(@Nullable String usuarioUsername) { this.usuarioUsername = usuarioUsername; }

    public Date getFecha() { return fecha; }
    public void setFecha(Date fecha) { this.fecha = fecha; }

    public boolean isStatus() { return status; }
    public void setStatus(boolean status) { this.status = status; }

    @Nullable
    public List<OrdenCompraLineaDTO> getDetalles() { return detalles; }
    public void setDetalles(@Nullable List<OrdenCompraLineaDTO> detalles) { this.detalles = detalles; }
}
