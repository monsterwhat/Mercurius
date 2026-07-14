package Controllers.UI;

import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "productPerformanceUIBean")
@ViewScoped
public class ProductPerformanceUIBean implements Serializable {

    @Nonnull
    private Date startDate;
    @Nonnull
    private Date endDate;
    private int totalProducts;
    @Nullable
    private String totalRevenue;
    @Nullable
    private String avgPrice;
    @Nullable
    private String topCategory;
    @Nonnull
    private List<ProductData> bestSellers;
    @Nonnull
    private List<ProductData> worstSellers;
    @Nonnull
    private List<ProductData> bestByRevenue;
    @Nonnull
    private List<DepartmentData> departmentPerformance;

    public ProductPerformanceUIBean() {
        endDate = new Date();
        long thirtyDays = 30L * 24 * 60 * 60 * 1000;
        startDate = new Date(System.currentTimeMillis() - thirtyDays);
        bestSellers = new ArrayList<>();
        worstSellers = new ArrayList<>();
        bestByRevenue = new ArrayList<>();
        departmentPerformance = new ArrayList<>();
    }

    public void loadData() {
        // Sample data - in production would call REST API
        if (bestSellers.isEmpty()) {
            bestSellers.add(new ProductData(1, "Producto A", 150, new BigDecimal("450000")));
            bestSellers.add(new ProductData(2, "Producto B", 120, new BigDecimal("360000")));
            bestSellers.add(new ProductData(3, "Producto C", 100, new BigDecimal("300000")));
            bestSellers.add(new ProductData(4, "Producto D", 85, new BigDecimal("255000")));
            bestSellers.add(new ProductData(5, "Producto E", 75, new BigDecimal("225000")));
            
            worstSellers.add(new ProductData(10, "Producto J", 2, new BigDecimal("6000")));
            worstSellers.add(new ProductData(9, "Producto I", 5, new BigDecimal("15000")));
            worstSellers.add(new ProductData(8, "Producto H", 8, new BigDecimal("24000")));
            
            bestByRevenue.add(new ProductData(1, "Producto A", 150, new BigDecimal("450000")));
            bestByRevenue.add(new ProductData(2, "Producto B", 120, new BigDecimal("360000")));
            bestByRevenue.add(new ProductData(6, "Producto F", 60, new BigDecimal("320000")));
            
            departmentPerformance.add(new DepartmentData("Electrónica", new BigDecimal("1500000"), "35%"));
            departmentPerformance.add(new DepartmentData("Ropa", new BigDecimal("1200000"), "28%"));
            departmentPerformance.add(new DepartmentData("Alimentos", new BigDecimal("900000"), "21%"));
            departmentPerformance.add(new DepartmentData("Otros", new BigDecimal("700000"), "16%"));
            
            totalProducts = 250;
            totalRevenue = "₡4,300,000";
            avgPrice = "₡17,200";
            topCategory = "Electrónica";
        }
    }

    @Getter @Setter @ToString @EqualsAndHashCode
    public static class ProductData {
        public int codigo;
        @Nonnull
        public String nombre;
        public int cantidad;
        @Nonnull
        public BigDecimal ingresos;

        public ProductData(int codigo, @Nonnull String nombre, int cantidad, @Nonnull BigDecimal ingresos) {
            this.codigo = codigo;
            this.nombre = nombre;
            this.cantidad = cantidad;
            this.ingresos = ingresos;
        }
    }

    @Getter @Setter @ToString @EqualsAndHashCode
    public static class DepartmentData {
        @Nonnull
        public String nombre;
        @Nonnull
        public BigDecimal ventas;
        @Nonnull
        public String porcentaje;

        public DepartmentData(@Nonnull String nombre, @Nonnull BigDecimal ventas, @Nonnull String porcentaje) {
            this.nombre = nombre;
            this.ventas = ventas;
            this.porcentaje = porcentaje;
        }
    }
}
