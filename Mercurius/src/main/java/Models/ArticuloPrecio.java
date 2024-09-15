package Models;


import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
import lombok.Data;

/**
 *
 * @author Al
 */

@Entity
@Data
public class ArticuloPrecio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private int id;

    @ManyToOne
    @JoinColumn(name = "articulo_id", nullable = false)
    private Articulos articulo;

    @Column(name = "precio_costo_sin_iva")
    private BigDecimal precioCostoSinIVA;

    @Column(name = "precio_costo_con_iva")
    private BigDecimal precioCostoConIVA;

    @Column(name = "porcentaje_utilidad")
    private BigDecimal porcentajeUtilidad;

    @Column(name = "precio_final")
    private BigDecimal precioFinal;

    @Column(nullable = false)
    @Temporal(TemporalType.TIMESTAMP)
    private Date fechaCompra;

    @ManyToOne
    @JoinColumn(name = "usuario_id")
    private Users usuario; // Who made the purchase or set the price

    @PrePersist
    protected void onCreate() {
        fechaCompra = new Date(); // Sets the current timestamp when creating the entity
    }
    
    @Override
    public String toString(){
        return "";
    }
}
