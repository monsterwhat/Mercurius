package Models.ComprobantesV44;

import Models.ComprobantesV44.Detalles.DetalleServicio;
import Models.ComprobantesV44.Encabezado.Encabezado;
import Models.ComprobantesV44.Resumen.ResumenFactura;
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

/**
 *
 * @author Al
 */

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "ComprobantesRecibidos")
@Entity
@Data
public class ComprobantesRecibidos {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Use auto-increment strategy
    private Long id;
    
@XmlElement(name = "Encabezado")
    @ToString.Exclude
    @OneToOne(fetch = FetchType.LAZY)
    private Encabezado encabezado;
    
@XmlElement(name = "DetalleServicio")
    @ToString.Exclude
    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.PERSIST)
    @JoinColumn(name = "detalle_servicio_id")
    private DetalleServicio detalles;
    
@XmlElement(name = "Resumen")
    @ToString.Exclude
    @OneToOne(fetch = FetchType.LAZY)
    private ResumenFactura resumen;
    
    private Boolean status;
    private Boolean processed;
    
    @Column(nullable = false) // Ensures the field cannot be null in the database
    private Boolean paid = false; // Default value for the field
    
    @Column(length = 50)
    private String user;
    
}
