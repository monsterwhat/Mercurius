package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * DTO de lista para Órdenes de Compra.
 * Espejo plano de Models.OrdenCompra para la tabla del listado
 * (secured/pages/Compras/Ordenes/index.xhtml, formulario ordenesForm).
 * Las relaciones (proveedor) van aplanadas, sin referencias a entidades.
 */
public class OrdenCompraDTO {
    private Long id;
    private String numeroOrden;
    @Nullable private Integer proveedorId;
    @Nullable private String proveedorNombre;
    private Date fechaOrden;
    private String estado; // BORRADOR, ENVIADA, CONFIRMADA, RECIBIDA, FACTURADA, CANCELADA
    @Nullable private BigDecimal totalEstimado;

    public OrdenCompraDTO() {}

    public OrdenCompraDTO(Long id, String numeroOrden, Integer proveedorId, String proveedorNombre,
                          Date fechaOrden, String estado, BigDecimal totalEstimado) {
        this.id = id;
        this.numeroOrden = numeroOrden;
        this.proveedorId = proveedorId;
        this.proveedorNombre = proveedorNombre;
        this.fechaOrden = fechaOrden;
        this.estado = estado;
        this.totalEstimado = totalEstimado;
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

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    @Nullable
    public BigDecimal getTotalEstimado() { return totalEstimado; }
    public void setTotalEstimado(@Nullable BigDecimal totalEstimado) { this.totalEstimado = totalEstimado; }
}
