package Models;


import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.Date;
import java.util.Objects;
import lombok.Data;

/**
 *
 * @author Al
 */

//Se utiliza un array para mantener un historico de precios,
//siempre el valor mas nuevo es el precio actual.
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
    private BigDecimal precioCostoSinIVA; //Lo que costo sin agregar el IVA de nuestra parte

    @Column(name = "precio_final")
    private BigDecimal precioFinal; // precioConUtilidad + el IVA;

    @Column(name = "porcentaje_utilidad")
    private BigDecimal porcentajeUtilidad; //% De utilidad

    @Column(name = "precio_con_utilidad")
    private BigDecimal precioConUtilidad; //Lo que costo + la utilidad (S/IVA)

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
    
    @Override
    public int hashCode() {
        return Objects.hash(id, precioFinal, precioConUtilidad, fechaCompra);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        ArticuloPrecio that = (ArticuloPrecio) obj;
        return id == that.id &&
               Objects.equals(precioFinal, that.precioFinal) &&
               Objects.equals(precioConUtilidad, that.precioConUtilidad) &&
               Objects.equals(fechaCompra, that.fechaCompra);
    }
    
    
}
