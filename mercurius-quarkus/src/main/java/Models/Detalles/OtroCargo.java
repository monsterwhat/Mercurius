package Models.Detalles;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "OtroCargo")
@Data
@Entity
@Table(name = "otro_cargo")
public class OtroCargo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
 
    @XmlTransient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "detalle_servicio_id")
    private DetalleServicio detalleServicio;

    @XmlElement(name = "TipoDocumentoOC")
    @Column(name = "tipo_documento_oc", length = 2)
    private String tipoDocumentoOC;
    
    @XmlElement(name = "TipoDocumentoOtros")
    @Column(name = "tipo_documento_OTROS", length = 100)
    private String tipoDocumentoOTROS;

    @XmlElement(name = "IdentificacionTercero")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "identificacion_tercero")
    private IdentificacionTercero identificacionTercero;

    @XmlElement(name = "NombreTercero")
    @Column(name = "nombre_tercero", length = 100)
    private String nombreTercero;

    @XmlElement(name = "Detalle")
    @Column(name = "detalle", length = 160)
    private String detalle;

    @XmlElement(name = "PorcentajeOC")
    @Column(name = "porcentaje_oc", precision = 9, scale = 5)
    private BigDecimal porcentajeOC;

    @XmlElement(name = "MontoCargo")
    @Column(name = "monto_cargo", precision = 18, scale = 5)
    private BigDecimal montoCargo;

}