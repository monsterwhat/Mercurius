package Utils;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import Models.Articulos.Articulos;
import java.math.BigDecimal;

/**
 * Data class for ABC analysis
 */
public class ABCAnalysis {
    @Nullable private Articulos articulo;
    @Nullable private String abcCategory;
    private int totalSold;
    @Nullable private BigDecimal avgQuantity;
    @Nullable private BigDecimal unitPrice;
    @Nullable private BigDecimal totalRevenue;
    @Nullable private String departamento;
    @Nullable private String familia;
    @Nullable private BigDecimal cumulativeRevenue;

    // Constructor and setters
    public ABCAnalysis(@Nullable Articulos articulo, @Nullable String abcCategory, int totalSold, @Nullable BigDecimal avgQuantity, 
                      @Nullable BigDecimal unitPrice, @Nullable BigDecimal totalRevenue, @Nullable String departamento, @Nullable String familia, @Nullable BigDecimal cumulativeRevenue) {
        this.articulo = articulo;
        this.abcCategory = abcCategory;
        this.totalSold = totalSold;
        this.avgQuantity = avgQuantity;
        this.unitPrice = unitPrice;
        this.totalRevenue = totalRevenue;
        this.departamento = departamento;
        this.familia = familia;
        this.cumulativeRevenue = cumulativeRevenue;
    }

    // Getters
    @Nullable public Articulos getArticulo() { return articulo; }
    @Nullable public String getAbcCategory() { return abcCategory; }
    public int getTotalSold() { return totalSold; }
    @Nullable public BigDecimal getAvgQuantity() { return avgQuantity; }
    @Nullable public BigDecimal getUnitPrice() { return unitPrice; }
    @Nullable public BigDecimal getTotalRevenue() { return totalRevenue; }
    @Nullable public String getDepartamento() { return departamento; }
    @Nullable public String getFamilia() { return familia; }
    @Nullable public BigDecimal getCumulativeRevenue() { return cumulativeRevenue; }
}