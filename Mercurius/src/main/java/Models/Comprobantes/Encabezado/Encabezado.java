package Models.Comprobantes.Encabezado;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.ToString;

/**
 *
 * @author Al
 */

@Data
@Entity
@Table(name = "Encabezado")
public class Encabezado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "codigo_actividad", length = 6)
    private String codigoActividad;

    @Column(name = "clave", length = 50)
    private String clave;

    @Column(name = "numero_consecutivo", length = 20)
    private String numeroConsecutivo;

    @Column(name = "fecha_emision")
    private LocalDateTime fechaEmision;

    @Column(name = "condicion_venta", length = 2)
    private String condicionVenta;

    @Column(name = "plazo_credito", length = 10)
    private String plazoCredito;

    @ToString.Exclude
    @OneToMany(mappedBy = "comprobante",fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<MedioPago> medioPago;

    @OneToOne()
    @JoinColumn(name = "emisor_id", referencedColumnName = "id")
    private Emisor emisor;

    @OneToOne()
    @JoinColumn(name = "receptor_id", referencedColumnName = "id")
    private Receptor receptor;
    
    @Override
    public String toString() {
        return "Encabezado{" +
                "id=" + id +
                ", codigoActividad='" + codigoActividad + '\'' +
                ", clave='" + clave + '\'' +
                ", numeroConsecutivo='" + numeroConsecutivo + '\'' +
                ", fechaEmision=" + fechaEmision +
                ", condicionVenta='" + condicionVenta + '\'' +
                ", plazoCredito='" + plazoCredito + '\'' +
                '}';
    }
}
