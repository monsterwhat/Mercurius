package Models.ComprobantesV45;

import Models.ComprobantesV45.Detalles.DetalleServicio;
import Models.ComprobantesV45.Encabezado.Encabezado;
import Models.ComprobantesV45.Resumen.ResumenFactura;
import Models.Users;
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
import lombok.Data;
import lombok.ToString;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "ComprobantesEmitidos")
@Entity(name = "ComprobantesEmitidosV45")
@Data
public class ComprobantesEmitidos {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
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
    
    private Boolean status;
    
    @Column(length = 50)
    private String user;
    
}
