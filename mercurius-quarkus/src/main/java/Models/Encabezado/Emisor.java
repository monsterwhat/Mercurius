package Models.Encabezado;

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
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.annotation.Nullable;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "Emisor")
@Data
@Entity
@Table(name = "emisor")
public class Emisor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @XmlTransient
    private Long id;

    @XmlElement(name = "Nombre")
    @Nullable
    @Column(name = "nombre", length = 100)
    private String nombre;

    @XmlElement(name = "Identificacion")
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "identificacion_emisor_id", referencedColumnName = "id")
    private IdentificacionEmisor identificacion;

    @XmlElement(name = "Registrofiscal8707")
    @Nullable
    @Column(name = "registro_fiscal8707", length = 12)
    private String Registrofiscal8707;

    @XmlElement(name = "NombreComercial")
    @Nullable
    @Column(name = "nombre_comercial", length = 80)
    private String nombreComercial;

    @XmlElement(name = "Ubicacion")
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "ubicacion_id", referencedColumnName = "id")
    private Ubicacion ubicacion;

    @XmlTransient
    @Nullable
    @Column(name = "otras_senas_extranjero", length = 300)
    private String OtrasSenasExtranjero;

    @XmlElement(name = "Telefono")
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "telefono_id", referencedColumnName = "id")
    private Telefono telefono;

    @XmlTransient
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "fax_id", referencedColumnName = "id")
    private Fax fax;

    @XmlElement(name = "CorreoElectronico")
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "emisor", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<CorreoElectronicoEmisor> correosElectronicos;

}