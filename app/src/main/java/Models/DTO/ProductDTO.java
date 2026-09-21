package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;

public class ProductDTO {
    private Long codigo;
    private String nombre;
    @Nullable private String descripcion;
    @Nullable private String codigoBarra;
    @Nullable private BigDecimal precio;
    @Nullable private String unidadMedida;
    @Nullable private String departamento;
    @Nullable private String familia;
    @Nullable private String imagenUrl;
    private boolean tieneStock;

    public ProductDTO() {}

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
    public String getUnidadMedida() { return unidadMedida; }
    public void setUnidadMedida(@Nullable String unidadMedida) { this.unidadMedida = unidadMedida; }

    @Nullable
    public String getDepartamento() { return departamento; }
    public void setDepartamento(@Nullable String departamento) { this.departamento = departamento; }

    @Nullable
    public String getFamilia() { return familia; }
    public void setFamilia(@Nullable String familia) { this.familia = familia; }

    @Nullable
    public String getImagenUrl() { return imagenUrl; }
    public void setImagenUrl(@Nullable String imagenUrl) { this.imagenUrl = imagenUrl; }

    public boolean isTieneStock() { return tieneStock; }
    public void setTieneStock(boolean tieneStock) { this.tieneStock = tieneStock; }
}
