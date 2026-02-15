package Models.Referencias;

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
import java.time.LocalDateTime;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "InformacionReferencia")
@Data
@Entity
@Table(name = "informacion_referencia")
public class InformacionReferencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "TipoDoc")
    @Column(name = "tipo_doc", length = 2)
    private String tipoDoc;

    @XmlElement(name = "Numero")
    @Column(name = "numero", length = 50)
    private String numero;

    @XmlElement(name = "FechaEmision")
    @Column(name = "fecha_emision")
    private LocalDateTime fechaEmision;

    @XmlElement(name = "Codigo")
    @Column(name = "codigo", length = 2)
    private String codigo;

    @XmlElement(name = "Razon")
    @Column(name = "razon", length = 180)
    private String razon;

}
