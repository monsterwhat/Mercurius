package Models.Comprobantes.Detalles;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.util.Date;
import java.util.List;
import lombok.Data;

/**
 *
 * @author Al
 */

@Data
@Entity
@Table(name = "detalle_servicio")
public class DetalleServicio {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    private Boolean status;

    @OneToMany(mappedBy = "detalleServicio", cascade = CascadeType.PERSIST, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<LineaDetalle> lineasDetalle;

    @OneToMany(mappedBy = "detalleServicio", cascade = CascadeType.PERSIST, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<OtroCargo> otrosCargos;
    
    @PrePersist
    protected void onCreate() {
        status = Boolean.TRUE;
    }

}
