package Models.ComprobantesV44.Encabezado;

import jakarta.persistence.*;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Entity
@Table(name = "correo_electronico_emisor")
@Data
@XmlAccessorType(XmlAccessType.FIELD)
public class CorreoElectronicoEmisor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "Correo")
    @Column(name = "correo", length = 160, nullable = false)
    private String correo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "emisor_id", nullable = false)
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    private Emisor emisor;
}
