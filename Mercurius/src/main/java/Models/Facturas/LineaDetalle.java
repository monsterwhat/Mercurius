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
    private BigDecimal cantidad;
    private String unidadMedida;
    private String unidadMedidaComercial;
    private String detalle;
    private BigDecimal precioUnitario;
    private BigDecimal montoTotal;
    private BigDecimal subTotal;

    @Embedded
    private Descuento descuento;

    @OneToMany(mappedBy = "lineaDetalle", cascade = CascadeType.ALL)
    private List<CodigoComercial> codigosComerciales;

}



