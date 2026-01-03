package Models.ComprobantesV45.Detalles;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.List;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 *
 * @author Al
 */

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "DetalleServicio")
@Data
@Entity(name = "DetalleServicioV45")
@Table(name = "detalle_servicio")
public class DetalleServicio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
@ToString.Exclude
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "comprobante_emitido_id")
    private Models.ComprobantesV45.ComprobantesEmitidos comprobanteEmitido;
    
    private Boolean status;

    @XmlElementWrapper(name = "LineasDetalle")
    @XmlElement(name = "LineaDetalle")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "detalleServicio", cascade = CascadeType.PERSIST, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<LineaDetalle> lineasDetalle;

    @XmlElementWrapper(name = "OtrosCargos")
    @XmlElement(name = "OtroCargo")
    @EqualsAndHashCode.Exclude
    @ToString.Exclude
    @OneToMany(mappedBy = "detalleServicio", cascade = CascadeType.PERSIST, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OtroCargo> otrosCargos;
    
    @PrePersist
    protected void onCreate() {
        status = Boolean.TRUE;
    }  
}
