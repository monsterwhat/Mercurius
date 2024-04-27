package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class CodigoComercial {
    private String tipo;
    private String codigo;
}
