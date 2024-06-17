package Models.Comprobantes.Encabezado;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.ToString;

/**
 *
 * @author Al
 */

@Data
@Entity
@Table(name = "medio_pago")
public class MedioPago {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "comprobante_id", nullable = false)
    @ToString.Exclude
    private Encabezado comprobante;

    @Column(name = "medio_pago", length = 2)
    private String medioPago;
    
    @Override
    public String toString() {
        return "MedioPago{" +
                "id=" + id +
                ", medioPago='" + medioPago + '\'' +
                '}';
    }
}
