package Controllers.UI;

import jakarta.faces.view.ViewScoped;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.Data;

@Data
@Named(value = "stockForecastUIBean")
@ViewScoped
public class StockForecastUIBean implements Serializable {

    private int forecastDays;
    @Nonnull
    private List<ForecastData> forecasts;
    @Nonnull
    private HealthReport healthReport;
    @Nonnull
    private List<ReorderData> reorderRecommendations;

    public StockForecastUIBean() {
        forecastDays = 30;
        forecasts = new ArrayList<>();
        healthReport = new HealthReport();
        reorderRecommendations = new ArrayList<>();
    }

    public void loadData() {
        // Sample data - in production would call REST API
        if (forecasts.isEmpty()) {
            forecasts.add(new ForecastData("Producto A", 50, 45, 3, "Comprar"));
            forecasts.add(new ForecastData("Producto B", 100, 30, 10, "Pronto"));
            forecasts.add(new ForecastData("Producto C", 200, 25, 25, "OK"));
            forecasts.add(new ForecastData("Producto D", 30, 40, 0, "Comprar"));
            forecasts.add(new ForecastData("Producto E", 150, 20, 20, "OK"));
            
            reorderRecommendations.add(new ReorderData("Producto A", 50, 100, new BigDecimal("500000"), "Urgente"));
            reorderRecommendations.add(new ReorderData("Producto D", 30, 80, new BigDecimal("400000"), "Urgente"));
            reorderRecommendations.add(new ReorderData("Producto B", 100, 50, new BigDecimal("250000"), "Alta"));
            
            healthReport = new HealthReport();
            healthReport.totalProducts = 250;
            healthReport.healthyProducts = 180;
            healthReport.lowStockProducts = 50;
            healthReport.outOfStockProducts = 20;
            
            healthReport.productHealth.add(new ProductHealth("Producto A", 50, 30, 1.5, 10, "Crítico"));
            healthReport.productHealth.add(new ProductHealth("Producto B", 100, 50, 3.0, 15, "Advertencia"));
            healthReport.productHealth.add(new ProductHealth("Producto C", 200, 100, 5.0, 40, "Saludable"));
            healthReport.productHealth.add(new ProductHealth("Producto D", 30, 20, 1.0, 5, "Crítico"));
        }
    }

    public void loadHealthReport() {
        loadData();
    }

    @Data
    public static class ForecastData {
        @Nonnull
        public String nombre;
        public int stockActual;
        public int demandaEstimada;
        public int diasRestantes;
        @Nonnull
        public String recomendacion;

        public ForecastData(@Nonnull String nombre, int stockActual, int demandaEstimada, int diasRestantes, @Nonnull String recomendacion) {
            this.nombre = nombre;
            this.stockActual = stockActual;
            this.demandaEstimada = demandaEstimada;
            this.diasRestantes = diasRestantes;
            this.recomendacion = recomendacion;
        }
    }

    @Data
    public static class HealthReport {
        public int totalProducts;
        public int healthyProducts;
        public int lowStockProducts;
        public int outOfStockProducts;
        @Nonnull
        public List<ProductHealth> productHealth = new ArrayList<>();
    }

    @Data
    public static class ProductHealth {
        @Nonnull
        public String nombre;
        public int stock;
        public int stockMinimo;
        public double velocidadVenta;
        public int diasRestantes;
        @Nonnull
        public String estado;

        public ProductHealth(@Nonnull String nombre, int stock, int stockMinimo, double velocidadVenta, int diasRestantes, @Nonnull String estado) {
            this.nombre = nombre;
            this.stock = stock;
            this.stockMinimo = stockMinimo;
            this.velocidadVenta = velocidadVenta;
            this.diasRestantes = diasRestantes;
            this.estado = estado;
        }
    }

    @Data
    public static class ReorderData {
        @Nonnull
        public String nombre;
        public int stockActual;
        public int cantidadSugerida;
        @Nonnull
        public BigDecimal costoEstimado;
        @Nonnull
        public String prioridad;

        public ReorderData(@Nonnull String nombre, int stockActual, int cantidadSugerida, @Nonnull BigDecimal costoEstimado, @Nonnull String prioridad) {
            this.nombre = nombre;
            this.stockActual = stockActual;
            this.cantidadSugerida = cantidadSugerida;
            this.costoEstimado = costoEstimado;
            this.prioridad = prioridad;
        }
    }
}
