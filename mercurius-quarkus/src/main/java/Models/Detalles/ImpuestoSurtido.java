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
import java.math.BigDecimal;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "ImpuestoSurtido")
@Data
@Entity
@Table(name = "impuesto_surtido")
public class ImpuestoSurtido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "detalle_surtido_id")
    private DetalleSurtido detalleSurtido;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "datos_impuesto_surtido_id")
    private DatosImpuestoEspecificoSurtido datosImpuestoEspecificoSurtido;

    @XmlElement(name = "CodigoImpuestoSurtido")
    @Column(name = "codigo_impuesto_surtido", length = 2)
    private String codigoImpuestoSurtido;

    @XmlElement(name = "CodigoImpuestoOtroSurtido")
    @Column(name = "codigo_impuesto_otro_surtido", length = 100)
    private String codigoImpuestoOTROSurtido;

    @XmlElement(name = "CodigoTarifaIvaSurtido")
    @Column(name = "codigo_tarifa_iva_surtido", length = 2)
    private String codigoTarifaIvaSurtido;

    @XmlElement(name = "TarifaSurtido")
    @Column(name = "tarifa_surtido", precision = 4, scale = 2)
    private BigDecimal tarifaSurtido;

    @XmlElement(name = "MontoImpuestoSurtido")
    @Column(name = "monto_impuesto_surtido", precision = 18, scale = 5)
    private BigDecimal montoImpuestoSurtido;

}
