package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class Descuento {
    private String monto;
    private String naturalezaDescuento;
}
