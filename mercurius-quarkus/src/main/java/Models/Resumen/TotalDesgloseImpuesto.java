package Models.Resumen;

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
@XmlRootElement(name = "TotalDesgloseImpuesto")
@Data
@Entity
@Table(name = "total_desglose_impuesto")
public class TotalDesgloseImpuesto {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @XmlElement(name = "Codigo")
    @Column(name = "codigo", length = 2)
    private String codigo;
    
    @XmlElement(name = "CodigoTarifaIVA")
    @Column(name = "codigo_tarifa_iva", length = 2)
    private String codigoTarifaIVA;
    
    @XmlElement(name = "TotalMontoImpuesto")
    @Column(name = "total_monto_impuesto", precision = 18, scale = 5)
    private BigDecimal totalMontoImpuesto;
      
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "resumen_factura_id")
    private ResumenFactura resumenFactura;
    
}
