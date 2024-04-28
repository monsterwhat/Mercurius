package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
class Impuesto {
   
    private String codigo;
    private String codigoTarifa;
    private String tarifa;
    private String monto;
    
}
