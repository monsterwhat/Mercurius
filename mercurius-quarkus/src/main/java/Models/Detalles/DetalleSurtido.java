package Models.Detalles;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id; 
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "DetalleSurtido")
@Data
@Entity
@Table(name = "detalle_surtido")
public class DetalleSurtido {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @XmlElement(name = "LineaDetalleSurtido")
    @Column(name = "linea_detalle_surtido")
    private Integer lineaDetalleSurtido;
    
    @XmlElement(name = "CodigoCabysSurtido")
    @Column(name = "codigo_Cabys_Surtido", length = 13)
    private String codigoCabysSurtido;
    
    @XmlElementWrapper(name = "CodigosComercialesSurtidos")
    @XmlElement(name = "CodigoComercialSurtido")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "detalleSurtido", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<CodigoComercialSurtido> codigosComercialesSurtidos;
    
    @XmlElement(name = "CantidadSurtido")
    @Column(name = "cantidad_surtido", precision = 16, scale = 3)
    private BigDecimal cantidadSurtido;

    @XmlElement(name = "UnidadMedidaSurtido")
    @Column(name = "unidad_medida_surtido", length = 15)
    private String unidadMedidaSurtido;
      
    @XmlElement(name = "UnidadMedidaComercialSurtido")
    @Column(name = "unidad_medida_comercial_surtido", length = 20)
    private String unidadMedidaComercialSurtido;

    @XmlElement(name = "DetalleSurtido")
    @Column(name = "detalle_surtido", length = 200)
    private String detalleSurtido;
        
    @XmlElement(name = "PrecioUnitarioSurtido")
    @Column(name = "precio_unitario_surtido", precision = 18, scale = 5)
    private BigDecimal precioUnitarioSurtido;
      
    @XmlElement(name = "MontoTotalSurtido")
    @Column(name = "monto_total_surtido", precision = 18, scale = 5)
    private BigDecimal montoTotalSurtido;

    @XmlElementWrapper(name = "DescuentosSurtidos")
    @XmlElement(name = "DescuentoSurtido")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "detalleSurtido", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<DescuentoSurtido> descuentosSurtidos;

    @XmlElement(name = "SubTotalSurtido")
    @Column(name = "sub_total_surtido", precision = 18, scale = 5)
    private BigDecimal subTotalSurtido;
    
    @XmlElement(name = "IVACobradoFabricaSurtido")
    @Column(name = "iva_cobrado_fabrica_surtido", length = 2)
    private String ivaCobradoFabricaSurtido;

    @XmlElement(name = "BaseImponibleSurtido")
    @Column(name = "base_imponible_surtido", precision = 18, scale = 5)
    private BigDecimal baseImponibleSurtido;
 
    @XmlElementWrapper(name = "ImpuestosSurtidos")
    @XmlElement(name = "ImpuestoSurtido")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "detalleSurtido", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    private List<ImpuestoSurtido> impuestosSurtidos;
    
    @XmlElement(name = "MontoTotalLinea")
    @Column(name = "monto_total_de_linea", precision = 18, scale = 5)
    private BigDecimal montoTotalLinea;

}
