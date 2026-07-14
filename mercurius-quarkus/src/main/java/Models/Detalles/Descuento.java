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
@XmlRootElement(name = "Descuento")
@Data
@Entity
@Table(name = "descuento")
public class Descuento {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "LineaDetalle")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "linea_detalle_id")
    private LineaDetalle lineaDetalle;

    @XmlElement(name = "MontoDescuento")
    @Column(name = "monto_descuento", precision = 18, scale = 5)
    private BigDecimal montoDescuento;
    
    @XmlElement(name = "CodigoDescuento")
    @Column(name = "codigo_descuento", length = 2)
    private String codigoDescuento;
    
    @XmlElement(name = "CodigoDescuentoOtro")
    @Column(name = "codigo_descuento_otro", length = 100)
    private String codigoDescuentoOtro;
      
    @XmlElement(name = "NaturalezaDescuento")
    @Column(name = "naturaleza_descuento", length = 80)
    private String naturalezaDescuento;

}