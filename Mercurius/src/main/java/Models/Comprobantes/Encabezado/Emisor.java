package Models.Comprobantes.Encabezado;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
@Entity
@Table(name = "emisor")
public class Emisor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "nombre", length = 100)
    private String nombre;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "identificacion_emisor_id", referencedColumnName = "id")
    private IdentificacionEmisor identificacion;

    @Column(name = "nombre_comercial", length = 80)
    private String nombreComercial;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "ubicacion_id", referencedColumnName = "id")
    private Ubicacion ubicacion;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "telefono_id", referencedColumnName = "id")
    private Telefono telefono;

    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "fax_id", referencedColumnName = "id")
    private Fax fax;

    @Column(name = "correo_electronico", length = 160)
    private String correoElectronico;
   
}
