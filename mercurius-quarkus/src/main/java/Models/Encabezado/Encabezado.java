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
import jakarta.xml.bind.annotation.XmlTransient;
import jakarta.xml.bind.annotation.XmlSchemaType;
import jakarta.annotation.Nullable;
import java.time.LocalDateTime;
import java.util.List;
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
    @XmlTransient
    @Nullable
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
 
    @XmlElement(name = "Clave")
    @Nullable
    @Column(name = "clave", length = 50)
    private String clave; 

    @XmlElement(name = "ProveedorSistemas")
    @Nullable
    @Column(name = "proveedor_sistemas", length = 20)
    private String proveedorSistemas;
      
    @XmlElement(name = "CodigoActividadEmisor")
    @Nullable
    @Column(name = "codigo_actividad_emisor", length = 6)
    private String codigoActividadEmisor;
    
    @XmlElement(name = "CodigoActividadReceptor")
    @Nullable
    @Column(name = "codigo_actividad_receptor", length = 6)
    private String codigoActividadReceptor; 
    
    @XmlElement(name = "NumeroConsecutivo")
    @Nullable
    @Column(name = "numero_consecutivo", length = 20)
    private String numeroConsecutivo;

    @XmlElement(name = "FechaEmision")
    @XmlSchemaType(name = "dateTime")
    @Nullable
    @Column(name = "fecha_emision")
    private LocalDateTime fechaEmision;
    
    @XmlElement(name = "Emisor")
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "emisor_id")  
    private Emisor emisor;
    
    @XmlElement(name = "Receptor")
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "receptor_id") 
    private Receptor receptor;

    @XmlElement(name = "CondicionVenta")
    @Nullable
    @Column(name = "condicion_venta", length = 2)
    private String condicionVenta;
    
    @XmlElement(name = "CondicionVentaOtros")
    @Nullable
    @Column(name = "condicion_venta_otros", length = 100)
    private String condicionVentaOtros;

    @XmlElement(name = "PlazoCredito")
    @Nullable
    @Column(name = "plazo_credito", length = 10)
    private String plazoCredito;

    @XmlTransient
    @Nullable
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "comprobante",fetch = FetchType.EAGER, cascade = CascadeType.ALL)
    private List<MedioPago> medioPago;
    
    @XmlTransient
    @Nullable
    @Column(name = "estado", length = 20)
    private String estado;
    
    @XmlTransient
    @Nullable
    @Column(name = "motivo_rechazo", length = 500)
    private String motivoRechazo;
    
    @XmlTransient
    @Nullable
    @Column(length = 10)
    private String schemaVersion;

    @XmlTransient
    @Nullable
    @Column(name = "codigo_documento", length = 2)
    private String codigoDocumento;
  
}
