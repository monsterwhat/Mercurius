package Models.Jaxb.ND;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class LineaDetalle {
    @XmlElement(name = "NumeroLinea")
    private Integer numeroLinea;

    @XmlElement(name = "PartidaArancelaria")
    private String partidaArancelaria;

    @XmlElement(name = "CodigoCABYS")
    private String codigoCabys;

    @XmlElement(name = "CodigoComercial")
    private List<CodigoComercial> codigosComerciales;

    @XmlElement(name = "Cantidad")
    private BigDecimal cantidad;

    @XmlElement(name = "UnidadMedida")
    private String unidadMedida;

    @XmlElement(name = "TipoTransaccion")
    private String tipoTransaccion;

    @XmlElement(name = "UnidadMedidaComercial")
    private String unidadMedidaComercial;

    @XmlElement(name = "Detalle")
    private String detalle;

    @XmlElement(name = "NumeroVINoSerie")
    private List<NumeroVINoSerie> numerosVINoSerie;

    @XmlElement(name = "RegistroMedicamento")
    private String registroMedicamento;

    @XmlElement(name = "FormaFarmaceutica")
    private String formaFarmaceutica;

    @XmlElement(name = "DetalleSurtido")
    private DetalleSurtido detalleSurtido;

    @XmlElement(name = "PrecioUnitario")
    private BigDecimal precioUnitario;

    @XmlElement(name = "MontoTotal")
    private BigDecimal montoTotal;

    @XmlElement(name = "Descuento")
    private List<Descuento> descuentos;

    @XmlElement(name = "SubTotal")
    private BigDecimal subTotal;

    @XmlElement(name = "IVACobradoFabrica")
    private String ivaCobradoFabrica;

    @XmlElement(name = "BaseImponible")
    private BigDecimal baseImponible;

    @XmlElement(name = "Impuesto")
    private List<Impuesto> impuestos;

    @XmlElement(name = "ImpuestoAsumidoEmisorFabrica")
    private BigDecimal impuestoAsumidoEmisorFabrica;

    @XmlElement(name = "ImpuestoNeto")
    private BigDecimal impuestoNeto;

    @XmlElement(name = "MontoTotalLinea")
    private BigDecimal montoTotalLinea;

    public LineaDetalle() {}

    public LineaDetalle(Models.Detalles.LineaDetalle src) {
        if (src != null) {
            this.numeroLinea = src.getNumeroLinea();
            this.partidaArancelaria = src.getPartidaArancelaria();
            this.codigoCabys = src.getCodigoCabys();
            this.cantidad = src.getCantidad();
            this.unidadMedida = src.getUnidadMedida();
            this.tipoTransaccion = src.getTipoTransaccion();
            this.unidadMedidaComercial = src.getUnidadMedidaComercial();
            this.detalle = src.getDetalle();
            this.registroMedicamento = src.getRegistroMedicamento();
            this.formaFarmaceutica = src.getFormaFarmaceutica();
            this.precioUnitario = src.getPrecioUnitario();
            this.montoTotal = src.getMontoTotal();
            this.subTotal = src.getSubTotal();
            this.ivaCobradoFabrica = src.getIvaCobradoFabrica();
            this.baseImponible = src.getBaseImponible();
            this.impuestoAsumidoEmisorFabrica = src.getImpuestoAsumidoEmisorFabrica();
            this.impuestoNeto = src.getImpuestoNeto();
            this.montoTotalLinea = src.getMontoTotalLinea();
            if (src.getCodigosComerciales() != null)
                this.codigosComerciales = src.getCodigosComerciales().stream()
                    .map(CodigoComercial::new).collect(Collectors.toList());
            if (src.getNumerosVINoSerie() != null)
                this.numerosVINoSerie = src.getNumerosVINoSerie().stream()
                    .map(NumeroVINoSerie::new).collect(Collectors.toList());
            if (src.getDescuentos() != null)
                this.descuentos = src.getDescuentos().stream()
                    .map(Descuento::new).collect(Collectors.toList());
            if (src.getImpuestos() != null)
                this.impuestos = src.getImpuestos().stream()
                    .map(Impuesto::new).collect(Collectors.toList());
            if (src.getDetalleSurtido() != null)
                this.detalleSurtido = new DetalleSurtido(src.getDetalleSurtido());
        }
    }
}
