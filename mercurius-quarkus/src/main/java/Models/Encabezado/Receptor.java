package Models.Encabezado;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.annotation.Nullable;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "Receptor")
@Data
@Entity
@Table(name = "receptor")
public class Receptor {

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
    @JoinColumn(name = "identificacion_receptor_id", referencedColumnName = "id")
    private IdentificacionReceptor identificacion;

    @XmlTransient
    @Nullable
    @Column(name = "identificacion_extranjero", length = 20)
    private String identificacionExtranjero;

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

    @XmlElement(name = "OtrasSenasExtranjero")
    @Nullable
    @Column(name = "otras_senas_extranjero", length = 300)
    private String otrasSenasExtranjero;

    @XmlElement(name = "Telefono")
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "telefono_id", referencedColumnName = "id")
    private Telefono telefono;
    
    @XmlElement(name = "CorreoElectronico")
    @Nullable
    @Column(name = "correo_electronico", length = 160)
    private String correoElectronico;
      
    @XmlTransient
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "fax_id", referencedColumnName = "id")
    private Fax fax;

}