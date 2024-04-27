package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class Telefono {
    private String codigoPais;
    private String numTelefono;
}
