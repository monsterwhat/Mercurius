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
@XmlRootElement(name = "Fax")
@Data
@Entity
@Table(name = "fax")
public class Fax {

    @Id
    @XmlTransient
    @Nullable
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "CodigoPais")
    @Nullable
    @Column(name = "codigo_pais", length = 3)
    private String codigoPais;

    @XmlElement(name = "NumeroFax")
    @Nullable
    @Column(name = "numero_fax", length = 20)
    private String numeroFax;

    @Nullable
    @Column(length = 10)
    private String schemaVersion;
}
