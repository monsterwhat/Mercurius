package Models.ComprobantesV45.Detalles;

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
import java.math.BigDecimal;
import lombok.Data;

/**
 *
 * @author Al
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "DatosImpuestoEspecificoSurtido")
@Data
@Entity(name = "DatosImpuestoEspecificoSurtidoV45")
@Table(name = "datos_impuesto_Surtido")
public class DatosImpuestoEspecificoSurtido {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @XmlElement(name = "NombreCampoXML")
    @Column(name = "cantidad_unidad_medida_surtido", precision = 7, scale = 2)
    private BigDecimal cantidadUnidadMedidaSurtido;
    
    @XmlElement(name = "NombreCampoXML")
    @Column(name = "porcentaje_surtido", precision = 4, scale = 2)
    private BigDecimal porcentajeSurtido;
    
    @XmlElement(name = "NombreCampoXML")
    @Column(name = "proporcion_surtido", precision = 5, scale = 2)
    private BigDecimal proporcionSurtido;
    
    @XmlElement(name = "NombreCampoXML")
    @Column(name = "volumen_unidad_consumo_surtido", precision = 7, scale = 2)
    private BigDecimal volumenUnidadConsumoSurtido;
    
    @XmlElement(name = "NombreCampoXML")
    @Column(name = "impuesto_unidad_surtido", precision = 18, scale = 2)
    private BigDecimal impuestoUnidadSurtido;
    
}
