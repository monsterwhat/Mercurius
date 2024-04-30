package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class Telefono {
    private String codigoPaisTelefono;
    private String numTelefono;
}
