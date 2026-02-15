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
@XmlRootElement(name = "MedioPago")
@Data
@Entity
@Table(name = "medio_pago_resumen")
public class MedioPagoR {
      
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @XmlElement(name = "TipoMedioPago")
    @Column(name = "tipo_medio_pago", length = 2)
    private String tipoMedioPago;
    
    @XmlElement(name = "MedioPagoOtros")
    @Column(name = "medio_pago_otros", length = 100)
    private String medioPagoOtros;
    
    @XmlElement(name = "TotalMedioPago")
    @Column(name = "total_medio_pago", precision = 18, scale = 5)
    private BigDecimal totalMedioPago;
      
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "resumen_factura_id")
    private ResumenFactura resumenFactura;
    
}
