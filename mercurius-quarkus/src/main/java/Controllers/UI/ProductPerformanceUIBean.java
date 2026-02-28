package Controllers.UI;

import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.Data;

@Data
@Named(value = "productPerformanceUIBean")
@ViewScoped
public class ProductPerformanceUIBean implements Serializable {

    private Date startDate;
    private Date endDate;
    private int totalProducts;
    private String totalRevenue;
    private String avgPrice;
    private String topCategory;
    private List<ProductData> bestSellers;
    private List<ProductData> worstSellers;
    private List<ProductData> bestByRevenue;
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

    @Data
    public static class ProductData {
        public int codigo;
        public String nombre;
        public int cantidad;
        public BigDecimal ingresos;

        public ProductData(int codigo, String nombre, int cantidad, BigDecimal ingresos) {
            this.codigo = codigo;
            this.nombre = nombre;
            this.cantidad = cantidad;
            this.ingresos = ingresos;
        }
    }

    @Data
    public static class DepartmentData {
        public String nombre;
        public BigDecimal ventas;
        public String porcentaje;

        public DepartmentData(String nombre, BigDecimal ventas, String porcentaje) {
            this.nombre = nombre;
            this.ventas = ventas;
            this.porcentaje = porcentaje;
        }
    }
}
