package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class Ubicacion {
    private String provincia;
    private String canton;
    private String distrito;
    private String barrio;
    private String otrasSenas;

}