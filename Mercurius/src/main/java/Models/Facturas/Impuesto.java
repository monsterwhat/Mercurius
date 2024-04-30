package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class Impuesto {
   
    private String codigoImpuesto;
    private String codigoTarifa;
    private String tarifa;
    private String monto;
    
}
