package Models;

import Models.Articulos.Articulos;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * Historical tracking of profit margins for articles
 * Tracks daily margin changes for analysis and reporting
 */
@Entity
@Data
@Table(name = "profit_margin_history")
public class ProfitMarginHistory implements Serializable {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    
    @ManyToOne
    @JoinColumn(name = "articulo_codigo", nullable = false)
    private Articulos articulo;
    
    @Column(name = "fecha", nullable = false)
    @Temporal(TemporalType.DATE)
    private Date fecha;
    
    @Column(name = "precio_costo", precision = 10, scale = 2)
    private BigDecimal precioCosto;
    
    @Column(name = "precio_venta", precision = 10, scale = 2)
    private BigDecimal precioVenta;
    
    @Column(name = "porcentaje_utilidad", precision = 5, scale = 2)
    private BigDecimal porcentajeUtilidad;
    
    @Column(name = "precio_con_utilidad", precision = 10, scale = 2)
    private BigDecimal precioConUtilidad;
    
    @Column(name = "margen_real", precision = 5, scale = 2)
    private BigDecimal margenReal;
    
    @Column(name = "cantidad_vendida")
    private Integer cantidadVendida;
    
    @Column(name = "total_ingresos", precision = 10, scale = 2)
    private BigDecimal totalIngresos;
    
    @Column(name = "fecha_creacion")
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCreacion;
    
    public ProfitMarginHistory() {
        this.fechaCreacion = new Date();
    }
    
    /**
     * Calculate real profit margin as a percentage
     * Real margin = (selling_price - cost_price) / selling_price * 100
     */
    public BigDecimal calculateMargenReal() {
        if (precioVenta == null || precioCosto == null || precioVenta.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        
        BigDecimal margen = precioVenta.subtract(precioCosto)
                .divide(precioVenta, 4, BigDecimal.ROUND_HALF_UP)
                .multiply(BigDecimal.valueOf(100));
        
        return margen;
    }
}