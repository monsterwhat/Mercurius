package Models.ComprobantesV45.Encabezado;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Data;

/**
 *
 * @author Al
 */

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "Ubicacion")
@Data
@Entity(name = "UbicacionV45")
@Table(name = "ubicacion")
public class Ubicacion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "Provincia")
    @Column(name = "provincia", length = 1)
    private String provincia;

    @XmlElement(name = "Canton")
    @Column(name = "canton", length = 2)
    private String canton;

    @XmlElement(name = "Distrito")
    @Column(name = "distrito", length = 2)
    private String distrito;

    @XmlElement(name = "Barrio")
    @Column(name = "barrio", length = 2)
    private String barrio;

    @XmlElement(name = "OtrasSenas")
    @Column(name = "otras_senas", length = 250)
    private String otrasSenas;
}
