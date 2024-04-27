package Models.Facturas;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.util.List;
import lombok.Data;

@Entity
@Data
public class DetalleServicio {
    @Id
    private Long id;

    @OneToMany(mappedBy = "detalleServicio", cascade = CascadeType.ALL)
    private List<LineaDetalle> lineasDetalle ;
}

