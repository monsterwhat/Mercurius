package Models;

import Models.Articulos.Articulos;
import Models.Users;
import jakarta.persistence.*;
import java.io.Serializable;
import java.util.Date;
import lombok.Data;

/**
 * Stock alerts for low inventory management
 * Tracks when items fall below minimum stock levels
 */
@Entity
@Data
@Table(name = "stock_alerts")
public class StockAlert implements Serializable {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    
    @ManyToOne
    @JoinColumn(name = "articulo_codigo", nullable = false)
    private Articulos articulo;
    
    @Column(name = "tipo_alerta", nullable = false)
    private String tipoAlerta; // 'low_stock', 'out_of_stock', 'reorder_suggestion'
    
    @Column(name = "cantidad_actual")
    private Integer cantidadActual;
    
    @Column(name = "cantidad_minima")
    private Integer cantidadMinima;
    
    @Column(name = "sugerido_reordenar")
    private Integer sugeridoReordenar;
    
    @ManyToOne
    @JoinColumn(name = "departamento_id")
    private Departamento departamento;
    
    @Column(name = "estado", nullable = false)
    private String estado; // 'active', 'acknowledged', 'resolved'
    
    @Column(name = "fecha_creacion", nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCreacion;
    
    @Column(name = "fecha_resolucion")
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaResolucion;
    
    @ManyToOne
    @JoinColumn(name = "usuario_resolucion")
    private Users usuarioResolucion;
    
    @Column(name = "notas")
    private String notas;
    
    public StockAlert() {
        this.fechaCreacion = new Date();
        this.estado = "active";
    }
}