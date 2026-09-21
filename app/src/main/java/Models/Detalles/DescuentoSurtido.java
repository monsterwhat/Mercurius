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
@XmlRootElement(name = "DescuentoSurtido")
@Data
@Entity
@Table(name = "descuento_surtido")
public class DescuentoSurtido {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlTransient
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "detalle_surtido_id")
    private LineaDetalleSurtido lineaDetalleSurtido;

    @XmlElement(name = "MontoDescuentoSurtido")
    @Column(name = "monto_descuento_surtido", precision = 18, scale = 5)
    private BigDecimal montoDescuentoSurtido;
    
    @XmlElement(name = "CodigoDescuentoSurtido")
    @Column(name = "codigo_descuento_surtido", length = 2)
    private String codigoDescuentoSurtido;
    
    @XmlElement(name = "DescuentoSurtidoOtros")
    @Column(name = "descuento_surtido_otros", length = 80)
    private String descuentoSurtidoOtros;
   
}