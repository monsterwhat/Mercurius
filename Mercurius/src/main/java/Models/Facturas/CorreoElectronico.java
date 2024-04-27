package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class CorreoElectronico {
    private String correoElectronico;
}
