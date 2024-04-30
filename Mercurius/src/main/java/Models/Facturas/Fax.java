package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

/**
 *
 * @author Al
 */
@Embeddable
@Data
public class Fax {
    private String codigoPaisFax;
    private String numFax;
}

