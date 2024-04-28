package Models.Facturas;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

@Entity
@Data
public class LineaDetalle {
    @Id
    private Long id;

    private int numeroLinea;
    private String codigo;
    @OneToMany(cascade = CascadeType.ALL)
    private List<CodigoComercial> codigosComerciales;
    private BigDecimal cantidad;
    private String unidadMedida;
    private String unidadMedidaComercial;
    private String detalle;
    private BigDecimal precioUnitario;
    private BigDecimal montoTotal;
    @Embedded private Descuento descuento;
    private BigDecimal subTotal;
    @Embedded private Impuesto impuesto;
    private BigDecimal impuestoNeto;
    private BigDecimal montoTotalLinea;

}



