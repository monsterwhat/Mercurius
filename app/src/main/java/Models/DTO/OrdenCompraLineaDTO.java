package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;

/**
 * DTO de línea (detalle) de una Orden de Compra.
 * Espejo plano de Models.OrdenCompraDetalle siguiendo el precedente de CartItemDTO:
 * artículo aplanado (código, nombre, código de barra), sin referencias a entidades.
 */
public class OrdenCompraLineaDTO {
    private Long id;
    @Nullable private Long articuloCodigo;
    @Nullable private String articuloNombre;
    @Nullable private String articuloCodigoBarra;
    private BigDecimal cantidad;
    private BigDecimal precioUnitario;
    private BigDecimal subtotal;
    @Nullable private String notas;

    public OrdenCompraLineaDTO() {}

    public OrdenCompraLineaDTO(Long id, Long articuloCodigo, String articuloNombre, String articuloCodigoBarra,
                               BigDecimal cantidad, BigDecimal precioUnitario, BigDecimal subtotal, String notas) {
        this.id = id;
        this.articuloCodigo = articuloCodigo;
        this.articuloNombre = articuloNombre;
        this.articuloCodigoBarra = articuloCodigoBarra;
        this.cantidad = cantidad;
        this.precioUnitario = precioUnitario;
        this.subtotal = subtotal;
        this.notas = notas;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    @Nullable
    public Long getArticuloCodigo() { return articuloCodigo; }
    public void setArticuloCodigo(@Nullable Long articuloCodigo) { this.articuloCodigo = articuloCodigo; }

    @Nullable
    public String getArticuloNombre() { return articuloNombre; }
    public void setArticuloNombre(@Nullable String articuloNombre) { this.articuloNombre = articuloNombre; }

    @Nullable
    public String getArticuloCodigoBarra() { return articuloCodigoBarra; }
    public void setArticuloCodigoBarra(@Nullable String articuloCodigoBarra) { this.articuloCodigoBarra = articuloCodigoBarra; }

    public BigDecimal getCantidad() { return cantidad; }
    public void setCantidad(BigDecimal cantidad) { this.cantidad = cantidad; }

    public BigDecimal getPrecioUnitario() { return precioUnitario; }
    public void setPrecioUnitario(BigDecimal precioUnitario) { this.precioUnitario = precioUnitario; }

    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }

    @Nullable
    public String getNotas() { return notas; }
    public void setNotas(@Nullable String notas) { this.notas = notas; }
}
