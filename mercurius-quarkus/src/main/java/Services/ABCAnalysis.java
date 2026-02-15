package Services;

import Models.Articulos.Articulos;
import java.math.BigDecimal;

/**
 * Data class for ABC analysis
 */
public class ABCAnalysis {
    private Articulos articulo;
    private String abcCategory;
    private int totalSold;
    private BigDecimal avgQuantity;
    private BigDecimal unitPrice;
    private BigDecimal totalRevenue;
    private String departamento;
    private String familia;
    private BigDecimal cumulativeRevenue;

    // Constructor and setters
    public ABCAnalysis(Articulos articulo, String abcCategory, int totalSold, BigDecimal avgQuantity, 
                      BigDecimal unitPrice, BigDecimal totalRevenue, String departamento, String familia, BigDecimal cumulativeRevenue) {
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
    public Articulos getArticulo() { return articulo; }
    public String getAbcCategory() { return abcCategory; }
    public int getTotalSold() { return totalSold; }
    public BigDecimal getAvgQuantity() { return avgQuantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getTotalRevenue() { return totalRevenue; }
    public String getDepartamento() { return departamento; }
    public String getFamilia() { return familia; }
    public BigDecimal getCumulativeRevenue() { return cumulativeRevenue; }
}