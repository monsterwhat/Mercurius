package Models.ComprobantesV45.Detalles;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
 import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.math.BigDecimal;
 import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 *
 * @author Al
 */

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "Impuesto")
@Data
@Entity(name = "ImpuestoV45")
@Table(name = "impuesto")
public class Impuesto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "linea_detalle_id")
    private LineaDetalle lineaDetalle;

    @XmlElement(name = "Codigo")
    @Column(name = "codigo", length = 2)
    private String codigo;
    
    @XmlElement(name = "CodigoImpuestoOtro")
    @Column(name = "codigo_impuesto_otro", length = 100)
    private String codigoImpuestoOtro;

    @XmlElement(name = "CodigoTarifaIVA")
    @Column(name = "codigo_tarifa_iva", length = 2)
    private String codigoTarifaIVA;

    @XmlElement(name = "Tarifa")
    @Column(name = "tarifa", precision = 4, scale = 2)
    private BigDecimal tarifa;

    @XmlElement(name = "FactorCalculoIVA")
    @Column(name = "factor_calculo_iva", precision = 5, scale = 4)
    private BigDecimal factorCalculoIVA;
    
    @XmlElement(name = "DatosImpuestoEspecifico")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "datos_impuesto_id")
    private DatosImpuestoEspecifico datosImpuestoEspeficio;
      
    @XmlElement(name = "Monto")
    @Column(name = "monto", precision = 18, scale = 5)
    private BigDecimal monto;

    @XmlElement(name = "MontoExportacion")
    @Column(name = "monto_exportacion", precision = 18, scale = 5)
    private BigDecimal montoExportacion;

    @XmlElement(name = "Exoneracion")
    @OneToOne(mappedBy = "impuesto", cascade = CascadeType.ALL, orphanRemoval = true)
    private Exoneracion exoneracion;
      
}
