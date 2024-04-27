package Models.Facturas;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import lombok.Data;

@Entity
@Data
public class Emisor {
    @Id
    private Long id;

    private String nombre;
    private String identificacionTipo;
    private String identificacionNumero;
    private String nombreComercial;

    @OneToOne(cascade = CascadeType.ALL)
    private Ubicacion ubicacion;

}
