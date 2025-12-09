package Models.ComprobantesV44.Encabezado;

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
@XmlRootElement(name = "Telefono")
@Data
@Entity
@Table(name = "telefono")
public class Telefono {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "CodigoPais")
    @Column(name = "codigo_pais", length = 3)
    private String codigoPais;

    @XmlElement(name = "NumeroTelefono")
    @Column(name = "numero_telefono", length = 20)
    private String numeroTelefono;
}
