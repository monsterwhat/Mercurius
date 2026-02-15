package Models.Encabezado;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "Encabezado")
@Data
@Entity
@Table(name = "encabezado")
public class Encabezado {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
 
    @XmlElement(name = "Clave")
    @Column(name = "clave", length = 50)
    private String clave; 

    @XmlElement(name = "ProveedorSistemas")
    @Column(name = "proveedor_sistemas", length = 20)
    private String proveedorSistemas;
      
    @XmlElement(name = "CodigoActividadEmisor")
    @Column(name = "codigo_actividad_emisor", length = 6)
    private String codigoActividadEmisor;
    
    @XmlElement(name = "CodigoActividadReceptor")
    @Column(name = "codigo_actividad_receptor", length = 6)
    private String codigoActividadReceptor; 
    
    @XmlElement(name = "NumeroConsecutivo")
    @Column(name = "numero_consecutivo", length = 20)
    private String numeroConsecutivo;

    @XmlElement(name = "FechaEmision")
    @Column(name = "fecha_emision")
    private LocalDateTime fechaEmision;
    
    @XmlElement(name = "Emisor")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "emisor_id")  
    private Emisor emisor;
    
    @XmlElement(name = "Receptor")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "receptor_id") 
    private Receptor receptor;

    @XmlElement(name = "CondicionVenta")
    @Column(name = "condicion_venta", length = 2)
    private String condicionVenta;
    
    @XmlElement(name = "CondicionVentaOtros")
    @Column(name = "condicion_venta_otros", length = 100)
    private String condicionVentaOtros;

    @XmlElement(name = "PlazoCredito")
    @Column(name = "plazo_credito", length = 10)
    private String plazoCredito;

    @XmlElementWrapper(name = "MediosPago")
    @XmlElement(name = "MedioPago")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "comprobante",fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<MedioPago> medioPago;
    
    @Column(name = "estado", length = 20)
    private String estado;
    
    @Column(name = "motivo_rechazo", length = 500)
    private String motivoRechazo;
    
    @Column(length = 10)
    private String schemaVersion;
  
}
