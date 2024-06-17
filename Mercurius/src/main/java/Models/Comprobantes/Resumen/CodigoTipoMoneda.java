package Models.Comprobantes.Resumen;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.math.BigDecimal;
import lombok.Data;

/**
 *
 * @author Al
 */

@Embeddable
@Data
public class CodigoTipoMoneda {

    @Column(name = "codigo_moneda", length = 3)
    private String codigoMoneda;

    @Column(name = "tipo_cambio_moneda", precision = 18, scale = 5)
    private BigDecimal tipoCambioMoneda;

}
