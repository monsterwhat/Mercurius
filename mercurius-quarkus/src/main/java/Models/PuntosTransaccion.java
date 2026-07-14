package Models;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 * Transaction record for customer loyalty points
 * Tracks earning, redemption, and expiration of points
 */
@Entity
@Data
@Table(name = "puntos_transacciones")
public class PuntosTransaccion implements Serializable {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;
    
    @ManyToOne
    @JoinColumn(name = "cliente_id")
    private Clients cliente;
    
    @Column(name = "tipo_transaccion")
    private String tipoTransaccion; // 'earn', 'redeem', 'expire'
    
    @Column(precision = 10, scale = 2)
    private BigDecimal puntos;
    
    @Column(name = "saldo_puntos", precision = 10, scale = 2)
    private BigDecimal saldoPuntos;
    
    private String descripcion;
    
    @Column(name = "factura_id")
    private String facturaId;
    
    @Column(name = "fecha_creacion")
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCreacion;
    
    public PuntosTransaccion() {
        this.fechaCreacion = new Date();
    }
}