package Models.Facturas;

import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.Data;

@Embeddable
@Data
public class Descuento {
    private BigDecimal monto;
    private String naturalezaDescuento;
}
