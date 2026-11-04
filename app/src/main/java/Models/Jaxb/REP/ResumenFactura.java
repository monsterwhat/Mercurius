package Models.Jaxb.REP;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * Resumen del Recibo Electrónico de Pago (v4.4).
 *
 * <p>El XSD del REP solo admite, en este orden: {@code CodigoTipoMoneda},
 * {@code TotalVenta}, {@code TotalVentaNeta}, {@code TotalDesgloseImpuesto*},
 * {@code TotalImpuesto?}, {@code MedioPago+} y {@code TotalComprobante}. No
 * existen los campos de servicios/mercancías, descuentos, IVA devuelto ni otros
 * cargos de los demás comprobantes; emitirlos rompía la validación XSD.</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@Data
@XmlType(propOrder = {
    "codigoMoneda",
    "totalVenta",
    "totalVentaNeta",
    "totalDesgloseImpuestos",
    "totalImpuesto",
    "mediosPago",
    "totalComprobante"
})
public class ResumenFactura {

    @XmlElement(name = "CodigoTipoMoneda")
    private CodigoTipoMoneda codigoMoneda;

    @XmlElement(name = "TotalVenta")
    private BigDecimal totalVenta;

    @XmlElement(name = "TotalVentaNeta")
    private BigDecimal totalVentaNeta;

    @XmlElement(name = "TotalDesgloseImpuesto")
    private List<TotalDesgloseImpuesto> totalDesgloseImpuestos;

    @XmlElement(name = "TotalImpuesto")
    private BigDecimal totalImpuesto;

    @XmlElement(name = "MedioPago")
    private List<MedioPagoR> mediosPago;

    @XmlElement(name = "TotalComprobante")
    private BigDecimal totalComprobante;

    public ResumenFactura() {
    }

    public ResumenFactura(Models.Resumen.ResumenFactura src) {
        if (src != null) {
            this.totalVenta = src.getTotalVenta();
            this.totalVentaNeta = src.getTotalVentaNeta();
            this.totalImpuesto = src.getTotalImpuesto();
            this.totalComprobante = src.getTotalComprobante();
            if (src.getCodigoMoneda() != null) {
                this.codigoMoneda = new CodigoTipoMoneda(src.getCodigoMoneda());
            }
            if (src.getTotalDesgloseImpuestos() != null) {
                this.totalDesgloseImpuestos = src.getTotalDesgloseImpuestos().stream()
                        .map(TotalDesgloseImpuesto::new).collect(Collectors.toList());
            }
            if (src.getMediosPago() != null) {
                this.mediosPago = src.getMediosPago().stream()
                        .map(MedioPagoR::new).collect(Collectors.toList());
            }
        }
    }
}
