package Models;

import Models.Articulos.Articulos;
import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * Reorder suggestions for inventory management
 * Calculates optimal reorder quantities based on sales velocity
 */
@Entity
@Data
@Table(name = "reorder_suggestions")
public class ReorderSuggestion implements Serializable {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    
    @ManyToOne
    @JoinColumn(name = "articulo_codigo", nullable = false)
    private Articulos articulo;
    
    @ManyToOne
    @JoinColumn(name = "departamento_id")
    private Departamento departamento;
    
    @Column(name = "cantidad_sugerida", nullable = false)
    private Integer cantidadSugerida;
    
    @Column(name = "costo_total_estimado", precision = 10, scale = 2)
    private BigDecimal costoTotalEstimado;
    
    @Column(name = "prioridad", nullable = false)
    private String prioridad; // 'low', 'medium', 'high', 'urgent'
    
    @Column(name = "dias_sin_stock")
    private Integer diasSinStock;
    
    @Column(name = "promedio_ventas_mensual", precision = 8, scale = 2)
    private BigDecimal promedioVentasMensual;
    
    @Column(name = "fecha_creacion", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCreacion;
    
    @Column(name = "fecha_ultimo_reabastecimiento")
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaUltimoReabastecimiento;
    
    @Column(name = "proveedor_sugerido")
    private String proveedorSugerido;
    
    @Column(name = "notas")
    private String notas;
    
    public ReorderSuggestion() {
        this.fechaCreacion = new Date();
        this.diasSinStock = 0;
    }
}