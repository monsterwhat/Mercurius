package Models.Comprobantes.Detalles;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
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

    @OneToMany(mappedBy = "detalleServicio", cascade = CascadeType.PERSIST, orphanRemoval = true)
    private List<LineaDetalle> lineasDetalle;

    @OneToMany(mappedBy = "detalleServicio", cascade = CascadeType.PERSIST, orphanRemoval = true)
    private List<OtroCargo> otrosCargos;
    
    private boolean enabled;

}
