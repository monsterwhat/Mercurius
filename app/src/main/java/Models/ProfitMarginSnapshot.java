package Models;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;
import jakarta.persistence.*;

/**
 * Daily snapshots of profit margins by department and family
 * Used for historical analysis and reporting
 */
@Entity
@Data
@Table(name = "profit_margin_snapshots")
public class ProfitMarginSnapshot implements Serializable {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    
    @Column(name = "fecha_snapshot", nullable = false)
    @Temporal(TemporalType.DATE)
    private Date fechaSnapshot;
    
    @Column(name = "departamento")
    private String departamento;
    
    @Column(name = "familia")
    private String familia;
    
    @Column(name = "margen_promedio", precision = 5, scale = 2)
    private BigDecimal margenPromedio;
    
    @Column(name = "total_utilidad", precision = 10, scale = 2)
    private BigDecimal totalUtilidad;
    
    @Column(name = "total_ventas", precision = 10, scale = 2)
    private BigDecimal totalVentas;
    
    @Column(name = "cantidad_articulos")
    private Integer cantidadArticulos;
    
    @Column(name = "fecha_creacion")
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCreacion;
    
    public ProfitMarginSnapshot() {
        this.fechaCreacion = new Date();
    }
}