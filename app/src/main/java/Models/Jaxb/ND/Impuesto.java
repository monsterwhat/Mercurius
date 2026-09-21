package Models.Jaxb.ND;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class Impuesto {
    @XmlElement(name = "Codigo")
    private String codigo;

    @XmlElement(name = "CodigoImpuestoOtro")
    private String codigoImpuestoOtro;

    @XmlElement(name = "CodigoTarifaIVA")
    private String codigoTarifaIVA;

    @XmlElement(name = "Tarifa")
    private BigDecimal tarifa;

    @XmlElement(name = "FactorCalculoIVA")
    private BigDecimal factorCalculoIVA;

    @XmlElement(name = "DatosImpuestoEspecifico")
    private DatosImpuestoEspecifico datosImpuestoEspeficio;

    @XmlElement(name = "Monto")
    private BigDecimal monto;

    @XmlElement(name = "MontoExportacion")
    private BigDecimal montoExportacion;

    @XmlElement(name = "Exoneracion")
    private Exoneracion exoneracion;

    public Impuesto() {}

    public Impuesto(Models.Detalles.Impuesto src) {
        if (src != null) {
            this.codigo = src.getCodigo();
            this.codigoImpuestoOtro = src.getCodigoImpuestoOtro();
            this.codigoTarifaIVA = src.getCodigoTarifaIVA();
            this.tarifa = src.getTarifa();
            this.factorCalculoIVA = src.getFactorCalculoIVA();
            this.monto = src.getMonto();
            this.montoExportacion = src.getMontoExportacion();
            if (src.getDatosImpuestoEspeficio() != null)
                this.datosImpuestoEspeficio = new DatosImpuestoEspecifico(src.getDatosImpuestoEspeficio());
            if (src.getExoneracion() != null)
                this.exoneracion = new Exoneracion(src.getExoneracion());
        }
    }
}
