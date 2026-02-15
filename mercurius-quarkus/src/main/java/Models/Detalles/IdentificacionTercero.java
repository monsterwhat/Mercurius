package Models.Detalles;

import jakarta.persistence.Basic;
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

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "IdentificacionTercero")
@Data
@Entity
@Table(name = "identificacion_tercero")
public class IdentificacionTercero {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "Tipo")
    @Basic
    @Column(name = "tipo", length = 2)
    private String tipo;
    
    @XmlElement(name = "Numero")
    @Basic
    @Column(name = "numero", length = 20)
    private String numero;
    
}
