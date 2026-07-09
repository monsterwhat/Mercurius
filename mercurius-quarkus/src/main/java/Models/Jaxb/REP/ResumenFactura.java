package Models.Jaxb.REP;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class ResumenFactura {
    @XmlElement(name = "CodigoTipoMoneda")
    private CodigoTipoMoneda codigoMoneda;

    @XmlElement(name = "TotalServGravados")
    private BigDecimal totalServGravados;

    @XmlElement(name = "TotalServExentos")
    private BigDecimal totalServExentos;

    @XmlElement(name = "TotalServExonerado")
    private BigDecimal totalServExonerado;

    @XmlElement(name = "TotalServNoSujeto")
    private BigDecimal totalServNoSujeto;

    @XmlElement(name = "TotalMercanciasGravadas")
    private BigDecimal totalMercanciasGravadas;

    @XmlElement(name = "TotalMercanciasExentas")
    private BigDecimal totalMercanciasExentas;

    @XmlElement(name = "TotalMercExonerada")
    private BigDecimal totalMercExonerada;

    @XmlElement(name = "TotalMercNoSujeta")
    private BigDecimal totalMercNoSujeta;

    @XmlElement(name = "TotalGravado")
    private BigDecimal totalGravado;

    @XmlElement(name = "TotalExento")
    private BigDecimal totalExento;

    @XmlElement(name = "TotalExonerado")
    private BigDecimal totalExonerado;

    @XmlElement(name = "TotalNoSujeto")
    private BigDecimal totalNoSujeto;

    @XmlElement(name = "TotalVenta")
    private BigDecimal totalVenta;

    @XmlElement(name = "TotalDescuentos")
    private BigDecimal totalDescuentos;

    @XmlElement(name = "TotalVentaNeta")
    private BigDecimal totalVentaNeta;

    @XmlElement(name = "TotalDesgloseImpuesto")
    private List<TotalDesgloseImpuesto> totalDesgloseImpuestos;

    @XmlElement(name = "TotalImpuesto")
    private BigDecimal totalImpuesto;

    @XmlElement(name = "TotalIVADevuelto")
    private BigDecimal totalIVADevuelto;

    @XmlElement(name = "TotalOtrosCargos")
    private BigDecimal totalOtrosCargos;

    @XmlElement(name = "MedioPago")
    private List<MedioPagoR> mediosPago;

    @XmlElement(name = "TotalComprobante")
    private BigDecimal totalComprobante;

    public ResumenFactura() {}

    public ResumenFactura(Models.Resumen.ResumenFactura src) {
        if (src != null) {
            this.totalServGravados = src.getTotalServGravados();
            this.totalServExentos = src.getTotalServExentos();
            this.totalServExonerado = src.getTotalServExonerado();
            this.totalServNoSujeto = src.getTotalServNoSujeto();
            this.totalMercanciasGravadas = src.getTotalMercanciasGravadas();
            this.totalMercanciasExentas = src.getTotalMercanciasExentas();
            this.totalMercExonerada = src.getTotalMercExonerada();
            this.totalMercNoSujeta = src.getTotalMercNoSujeta();
            this.totalGravado = src.getTotalGravado();
            this.totalExento = src.getTotalExento();
            this.totalExonerado = src.getTotalExonerado();
            this.totalNoSujeto = src.getTotalNoSujeto();
            this.totalVenta = src.getTotalVenta();
            this.totalDescuentos = src.getTotalDescuentos();
            this.totalVentaNeta = src.getTotalVentaNeta();
            this.totalImpuesto = src.getTotalImpuesto();
            this.totalIVADevuelto = src.getTotalIVADevuelto();
            this.totalOtrosCargos = src.getTotalOtrosCargos();
            this.totalComprobante = src.getTotalComprobante();
            if (src.getCodigoMoneda() != null)
                this.codigoMoneda = new CodigoTipoMoneda(src.getCodigoMoneda());
            if (src.getTotalDesgloseImpuestos() != null)
                this.totalDesgloseImpuestos = src.getTotalDesgloseImpuestos().stream()
                    .map(TotalDesgloseImpuesto::new).collect(Collectors.toList());
            if (src.getMediosPago() != null)
                this.mediosPago = src.getMediosPago().stream()
                    .map(MedioPagoR::new).collect(Collectors.toList());
        }
    }
}
