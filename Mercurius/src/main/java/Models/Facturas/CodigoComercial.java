package Models.Facturas;

import jakarta.persistence.Embeddable;
import lombok.Data;

@Embeddable
@Data
public class CodigoComercial {

    private String tipoCodigo;
    private String codigoComercial;

}
