package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.List;

public class ProductDetailDTO {
    private Long codigo;
    private String nombre;
    @Nullable private String descripcion;
    @Nullable private String codigoBarra;
    @Nullable private BigDecimal precio;
    @Nullable private BigDecimal precioCostoSinIVA;
    @Nullable private BigDecimal porcentajeUtilidad;
    @Nullable private String unidadMedida;
    @Nullable private String departamento;
    @Nullable private String familia;
    @Nullable private List<String> imagenes;
    @Nullable private BigDecimal stockDisponible;
    private boolean status;
    private boolean processed;

    public ProductDetailDTO() {}

    public Long getCodigo() { return codigo; }
    public void setCodigo(Long codigo) { this.codigo = codigo; }

    public String getNombre() { return nombre; }
    public void setNombre(String nombre) { this.nombre = nombre; }

    @Nullable
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(@Nullable String descripcion) { this.descripcion = descripcion; }

    @Nullable
    public String getCodigoBarra() { return codigoBarra; }
    public void setCodigoBarra(@Nullable String codigoBarra) { this.codigoBarra = codigoBarra; }

    @Nullable
    public BigDecimal getPrecio() { return precio; }
    public void setPrecio(@Nullable BigDecimal precio) { this.precio = precio; }

    @Nullable
    public BigDecimal getPrecioCostoSinIVA() { return precioCostoSinIVA; }
    public void setPrecioCostoSinIVA(@Nullable BigDecimal precioCostoSinIVA) { this.precioCostoSinIVA = precioCostoSinIVA; }

    @Nullable
    public BigDecimal getPorcentajeUtilidad() { return porcentajeUtilidad; }
    public void setPorcentajeUtilidad(@Nullable BigDecimal porcentajeUtilidad) { this.porcentajeUtilidad = porcentajeUtilidad; }

    @Nullable
    public String getUnidadMedida() { return unidadMedida; }
    public void setUnidadMedida(@Nullable String unidadMedida) { this.unidadMedida = unidadMedida; }

    @Nullable
    public String getDepartamento() { return departamento; }
    public void setDepartamento(@Nullable String departamento) { this.departamento = departamento; }

    @Nullable
    public String getFamilia() { return familia; }
    public void setFamilia(@Nullable String familia) { this.familia = familia; }

    @Nullable
    public List<String> getImagenes() { return imagenes; }
    public void setImagenes(@Nullable List<String> imagenes) { this.imagenes = imagenes; }

    @Nullable
    public BigDecimal getStockDisponible() { return stockDisponible; }
    public void setStockDisponible(@Nullable BigDecimal stockDisponible) { this.stockDisponible = stockDisponible; }

    public boolean isStatus() { return status; }
    public void setStatus(boolean status) { this.status = status; }

    public boolean isProcessed() { return processed; }
    public void setProcessed(boolean processed) { this.processed = processed; }
}
