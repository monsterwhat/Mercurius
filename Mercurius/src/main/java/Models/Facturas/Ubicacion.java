package Models.Facturas;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Entity
@Data
public class Ubicacion {
    @Id
    private Long id;

    private String provincia;
    private String canton;
    private String distrito;
    private String barrio;
    private String otrasSenas;

}