package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Read-side view of a profit margin history record (entity
 * {@code Models.ProfitMarginHistory}) for the Márgenes de Utilidad report page.
 *
 * Mapping notes (entity -> DTO):
 * - id                -> id
 * - articulo          -> flattened into articuloCodigo (the {@code Articulos}
 *                        PK {@code Long codigo}), articuloDescripcion and
 *                        articuloNombre; its departamento/familia relations are
 *                        flattened into departamentoNombre / familiaNombre
 * - fecha             -> fecha
 * - precioCosto       -> precioCosto
 * - precioVenta       -> precioVenta
 * - porcentajeUtilidad-> porcentajeUtilidad
 * - precioConUtilidad -> precioConUtilidad
 * - margenReal        -> margenReal
 * - cantidadVendida   -> cantidadVendida
 * - totalIngresos     -> totalIngresos
 * - fechaCreacion     -> fechaCreacion
 */
public class ProfitMarginHistoryDTO {
    private int id;
    private Long articuloCodigo;
    private String articuloNombre;
    @Nullable private String articuloDescripcion;
    @Nullable private String departamentoNombre;
    @Nullable private String familiaNombre;
    private Date fecha;
    @Nullable private BigDecimal precioCosto;
    @Nullable private BigDecimal precioVenta;
    @Nullable private BigDecimal porcentajeUtilidad;
    @Nullable private BigDecimal precioConUtilidad;
    @Nullable private BigDecimal margenReal;
    @Nullable private Integer cantidadVendida;
    @Nullable private BigDecimal totalIngresos;
    @Nullable private Date fechaCreacion;

    public ProfitMarginHistoryDTO() {}

    public ProfitMarginHistoryDTO(int id, Long articuloCodigo, String articuloNombre,
                                  @Nullable String articuloDescripcion,
                                  @Nullable String departamentoNombre, @Nullable String familiaNombre,
                                  Date fecha, @Nullable BigDecimal precioCosto,
                                  @Nullable BigDecimal precioVenta, @Nullable BigDecimal porcentajeUtilidad,
                                  @Nullable BigDecimal precioConUtilidad, @Nullable BigDecimal margenReal,
                                  @Nullable Integer cantidadVendida, @Nullable BigDecimal totalIngresos,
                                  @Nullable Date fechaCreacion) {
        this.id = id;
        this.articuloCodigo = articuloCodigo;
        this.articuloNombre = articuloNombre;
        this.articuloDescripcion = articuloDescripcion;
        this.departamentoNombre = departamentoNombre;
        this.familiaNombre = familiaNombre;
        this.fecha = fecha;
        this.precioCosto = precioCosto;
        this.precioVenta = precioVenta;
        this.porcentajeUtilidad = porcentajeUtilidad;
        this.precioConUtilidad = precioConUtilidad;
        this.margenReal = margenReal;
        this.cantidadVendida = cantidadVendida;
        this.totalIngresos = totalIngresos;
        this.fechaCreacion = fechaCreacion;
    }

    public int getId() { return id; }
    public void setId(int id) { this.id = id; }

    public Long getArticuloCodigo() { return articuloCodigo; }
    public void setArticuloCodigo(Long articuloCodigo) { this.articuloCodigo = articuloCodigo; }

    public String getArticuloNombre() { return articuloNombre; }
    public void setArticuloNombre(String articuloNombre) { this.articuloNombre = articuloNombre; }

    @Nullable
    public String getArticuloDescripcion() { return articuloDescripcion; }
    public void setArticuloDescripcion(@Nullable String articuloDescripcion) { this.articuloDescripcion = articuloDescripcion; }

    @Nullable
    public String getDepartamentoNombre() { return departamentoNombre; }
    public void setDepartamentoNombre(@Nullable String departamentoNombre) { this.departamentoNombre = departamentoNombre; }

    @Nullable
    public String getFamiliaNombre() { return familiaNombre; }
    public void setFamiliaNombre(@Nullable String familiaNombre) { this.familiaNombre = familiaNombre; }

    public Date getFecha() { return fecha; }
    public void setFecha(Date fecha) { this.fecha = fecha; }

    @Nullable
    public BigDecimal getPrecioCosto() { return precioCosto; }
    public void setPrecioCosto(@Nullable BigDecimal precioCosto) { this.precioCosto = precioCosto; }

    @Nullable
    public BigDecimal getPrecioVenta() { return precioVenta; }
    public void setPrecioVenta(@Nullable BigDecimal precioVenta) { this.precioVenta = precioVenta; }

    @Nullable
    public BigDecimal getPorcentajeUtilidad() { return porcentajeUtilidad; }
    public void setPorcentajeUtilidad(@Nullable BigDecimal porcentajeUtilidad) { this.porcentajeUtilidad = porcentajeUtilidad; }

    @Nullable
    public BigDecimal getPrecioConUtilidad() { return precioConUtilidad; }
    public void setPrecioConUtilidad(@Nullable BigDecimal precioConUtilidad) { this.precioConUtilidad = precioConUtilidad; }

    @Nullable
    public BigDecimal getMargenReal() { return margenReal; }
    public void setMargenReal(@Nullable BigDecimal margenReal) { this.margenReal = margenReal; }

    @Nullable
    public Integer getCantidadVendida() { return cantidadVendida; }
    public void setCantidadVendida(@Nullable Integer cantidadVendida) { this.cantidadVendida = cantidadVendida; }

    @Nullable
    public BigDecimal getTotalIngresos() { return totalIngresos; }
    public void setTotalIngresos(@Nullable BigDecimal totalIngresos) { this.totalIngresos = totalIngresos; }

    @Nullable
    public Date getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(@Nullable Date fechaCreacion) { this.fechaCreacion = fechaCreacion; }
}
