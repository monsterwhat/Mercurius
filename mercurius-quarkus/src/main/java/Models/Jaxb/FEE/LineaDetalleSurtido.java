package Models.Jaxb.FEE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class LineaDetalleSurtido {
    @XmlElement(name = "LineaDetalleSurtido")
    private Integer lineaDetalleSurtido;

    @XmlElement(name = "CodigoCabysSurtido")
    private String codigoCabysSurtido;

    @XmlElementWrapper(name = "CodigosComercialesSurtidos")
    @XmlElement(name = "CodigoComercialSurtido")
    private List<CodigoComercialSurtido> codigosComercialesSurtidos;

    @XmlElement(name = "CantidadSurtido")
    private BigDecimal cantidadSurtido;

    @XmlElement(name = "UnidadMedidaSurtido")
    private String unidadMedidaSurtido;

    @XmlElement(name = "UnidadMedidaComercialSurtido")
    private String unidadMedidaComercialSurtido;

    @XmlElement(name = "DetalleSurtido")
    private String detalleSurtido;

    @XmlElement(name = "PrecioUnitarioSurtido")
    private BigDecimal precioUnitarioSurtido;

    @XmlElement(name = "MontoTotalSurtido")
    private BigDecimal montoTotalSurtido;

    @XmlElementWrapper(name = "DescuentosSurtidos")
    @XmlElement(name = "DescuentoSurtido")
    private List<DescuentoSurtido> descuentosSurtidos;

    @XmlElement(name = "SubTotalSurtido")
    private BigDecimal subTotalSurtido;

    @XmlElement(name = "IVACobradoFabricaSurtido")
    private String ivaCobradoFabricaSurtido;

    @XmlElement(name = "BaseImponibleSurtido")
    private BigDecimal baseImponibleSurtido;

    @XmlElementWrapper(name = "ImpuestosSurtidos")
    @XmlElement(name = "ImpuestoSurtido")
    private List<ImpuestoSurtido> impuestosSurtidos;

    @XmlElement(name = "MontoTotalLinea")
    private BigDecimal montoTotalLinea;

    public LineaDetalleSurtido() {}

    public LineaDetalleSurtido(Models.Detalles.LineaDetalleSurtido src) {
        if (src != null) {
            this.lineaDetalleSurtido = src.getLineaDetalleSurtido();
            this.codigoCabysSurtido = src.getCodigoCabysSurtido();
            this.cantidadSurtido = src.getCantidadSurtido();
            this.unidadMedidaSurtido = src.getUnidadMedidaSurtido();
            this.unidadMedidaComercialSurtido = src.getUnidadMedidaComercialSurtido();
            this.detalleSurtido = src.getDetalleSurtido();
            this.precioUnitarioSurtido = src.getPrecioUnitarioSurtido();
            this.montoTotalSurtido = src.getMontoTotalSurtido();
            this.subTotalSurtido = src.getSubTotalSurtido();
            this.ivaCobradoFabricaSurtido = src.getIvaCobradoFabricaSurtido();
            this.baseImponibleSurtido = src.getBaseImponibleSurtido();
            this.montoTotalLinea = src.getMontoTotalLinea();
            if (src.getCodigosComercialesSurtidos() != null)
                this.codigosComercialesSurtidos = src.getCodigosComercialesSurtidos().stream()
                    .map(CodigoComercialSurtido::new).collect(Collectors.toList());
            if (src.getDescuentosSurtidos() != null)
                this.descuentosSurtidos = src.getDescuentosSurtidos().stream()
                    .map(DescuentoSurtido::new).collect(Collectors.toList());
            if (src.getImpuestosSurtidos() != null)
                this.impuestosSurtidos = src.getImpuestosSurtidos().stream()
                    .map(ImpuestoSurtido::new).collect(Collectors.toList());
        }
    }
}
