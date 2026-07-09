package Models.Encabezado;

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
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.annotation.Nullable;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "Ubicacion")
@Data
@Entity
@Table(name = "ubicacion")
public class Ubicacion {

    @Id
    @XmlTransient
    @Nullable
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "Provincia")
    @Nullable
    @Column(name = "provincia", length = 1)
    private String provincia;

    @XmlElement(name = "Canton")
    @Nullable
    @Column(name = "canton", length = 2)
    private String canton;

    @XmlElement(name = "Distrito")
    @Nullable
    @Column(name = "distrito", length = 2)
    private String distrito;

    @XmlElement(name = "Barrio")
    @Nullable
    @Column(name = "barrio", length = 2)
    private String barrio;

    @XmlElement(name = "OtrasSenas")
    @Nullable
    @Column(name = "otras_senas", length = 250)
    private String otrasSenas;

    @Nullable
    @Column(length = 10)
    private String schemaVersion;
}
