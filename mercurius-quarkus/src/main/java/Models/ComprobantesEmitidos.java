package Models;

import Models.Detalles.DetalleServicio;
import Models.Encabezado.Encabezado;
import Models.Referencias.InformacionReferencia;
import Models.Resumen.ResumenFactura;
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
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "ComprobantesEmitidos")
@Entity
@Data
public class ComprobantesEmitidos {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(length = 10)
    private String schemaVersion;
    
    @XmlElement(name = "Encabezado")
    @ToString.Exclude
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private Encabezado encabezado;
    
    @XmlElement(name = "DetalleServicio")
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "detalle_servicio_id")
    private DetalleServicio detalles;
    
    @XmlElement(name = "Resumen")
    @ToString.Exclude
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private ResumenFactura resumen;

    @XmlTransient
    @ToString.Exclude
    @jakarta.persistence.OneToMany(cascade = CascadeType.ALL)
    @jakarta.persistence.JoinColumn(name = "comprobante_emitido_id")
    private java.util.List<InformacionReferencia> informacionReferencia;

    private Boolean status;
    
    @Column(length = 50)
    private String user;

    @XmlTransient
    @Column(length = 50)
    private String haciendaClave;

    @XmlTransient
    @Column(length = 20)
    private String haciendaEstado;

    @XmlTransient
    private LocalDateTime haciendaFechaEnvio;

    @XmlTransient
    private LocalDateTime haciendaFechaRespuesta;

    @XmlTransient
    @Column(name = "correction_attempts")
    private Integer correctionAttempts = 0;

    @XmlTransient
    @Column(name = "ultima_correccion")
    private LocalDateTime ultimaCorreccion;
    
}
