package Models.Facturas;

import jakarta.persistence.CascadeType;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import java.util.List;
import lombok.Data;

@Entity
@Data
public class LineaDetalle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // Use auto-increment strategy
    private Long id;

    private int numeroLinea;
    private String codigo;
    @ElementCollection private List<CodigoComercial> codigosComerciales;
    private String cantidad;
    private String unidadMedida;
    private String unidadMedidaComercial;
    private String detalle;
    private String precioUnitario;
    private String montoTotal;
    @ElementCollection private List<Descuento> descuentos;
    private String subTotal;
    @Embedded private Impuesto impuesto;
    private String impuestoNeto;
    private String montoTotalLinea;
    
    @ManyToOne(cascade = CascadeType.ALL)
    @JoinColumn(name = "detalleservicio_id")
    private DetalleServicio detalleServicio;


}



