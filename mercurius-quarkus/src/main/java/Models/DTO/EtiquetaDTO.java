package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;

/**
 * Printable price-label row for the /api/app/etiquetas surface.
 * Mirrors the exact column set of the legacy
 * {@code EtiquetasController.getFilteredArticulos()} table (codigo, nombre,
 * codigoBarra, precio final, familia, departamento) with relations flattened
 * per the LoteDTO convention (familia → familiaNombre,
 * departamento → departamentoNombre; nested entities intentionally excluded).
 *
 * <p>{@code cantidad} is the number of labels to generate for the article
 * (the legacy surface had no per-article quantity — printing produced one
 * pass over the whole selection — so API callers defaulting to 1 reproduce
 * the legacy behavior exactly).</p>
 */
public class EtiquetaDTO {

    @Nonnull private Long articuloCodigo; // Articulos.codigo
    @Nonnull private String nombre; // Articulos.nombre

    @Nullable private String codigoBarra; // Articulos.codigoBarra
    @Nullable private BigDecimal precioFinal; // Articulos.getLastPrecio().getPrecioFinal()
    @Nullable private String familiaNombre; // Articulos.familia.nombre
    @Nullable private String departamentoNombre; // Articulos.departamento.nombre

    private int cantidad; // labels to generate (legacy default: 1)

    public EtiquetaDTO() {}

    public EtiquetaDTO(@Nonnull Long articuloCodigo, @Nonnull String nombre,
                       @Nullable String codigoBarra, @Nullable BigDecimal precioFinal,
                       @Nullable String familiaNombre, @Nullable String departamentoNombre,
                       int cantidad) {
        this.articuloCodigo = articuloCodigo;
        this.nombre = nombre;
        this.codigoBarra = codigoBarra;
        this.precioFinal = precioFinal;
        this.familiaNombre = familiaNombre;
        this.departamentoNombre = departamentoNombre;
        this.cantidad = cantidad;
    }

    @Nonnull
    public Long getArticuloCodigo() { return articuloCodigo; }
    public void setArticuloCodigo(@Nonnull Long articuloCodigo) { this.articuloCodigo = articuloCodigo; }

    @Nonnull
    public String getNombre() { return nombre; }
    public void setNombre(@Nonnull String nombre) { this.nombre = nombre; }

    @Nullable
    public String getCodigoBarra() { return codigoBarra; }
    public void setCodigoBarra(@Nullable String codigoBarra) { this.codigoBarra = codigoBarra; }

    @Nullable
    public BigDecimal getPrecioFinal() { return precioFinal; }
    public void setPrecioFinal(@Nullable BigDecimal precioFinal) { this.precioFinal = precioFinal; }

    @Nullable
    public String getFamiliaNombre() { return familiaNombre; }
    public void setFamiliaNombre(@Nullable String familiaNombre) { this.familiaNombre = familiaNombre; }

    @Nullable
    public String getDepartamentoNombre() { return departamentoNombre; }
    public void setDepartamentoNombre(@Nullable String departamentoNombre) { this.departamentoNombre = departamentoNombre; }

    public int getCantidad() { return cantidad; }
    public void setCantidad(int cantidad) { this.cantidad = cantidad; }
}
