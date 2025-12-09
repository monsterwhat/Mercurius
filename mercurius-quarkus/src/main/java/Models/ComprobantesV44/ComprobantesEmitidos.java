package Models.ComprobantesV44;

import Models.ComprobantesV44.Detalles.DetalleServicio;
import Models.ComprobantesV44.Encabezado.Encabezado;
import Models.ComprobantesV44.Resumen.ResumenFactura;
import Models.Users;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "ComprobantesEmitidos")
@Entity
@Data
public class ComprobantesEmitidos {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @XmlElement(name = "Encabezado")
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private Encabezado encabezado;
    
    @XmlElement(name = "DetalleServicio")
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL, mappedBy = "comprobanteEmitido")
    private DetalleServicio detalles;
    
    @XmlElement(name = "Resumen")
    @OneToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    private ResumenFactura resumen;
    
    private Boolean status;
    
    @Column(length = 50)
    private String user;
    
}
