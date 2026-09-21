package Models.Jaxb.FEE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * FEE (Factura Electrónica de Exportación) Exoneración.
 * FEE XSD ExoneracionType has different field names than FE/NC/ND:
 * - TipoDocumento (not TipoDocumentoEX1)
 * - FechaEmision (not FechaEmisionEX)
 * - PorcentajeExoneracion (not TarifaExonerada)
 * - No TipoDocumentoOTRO, Articulo, Inciso, or NombreInstitucionOtros
 */
@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class Exoneracion {
    @XmlElement(name = "TipoDocumento")
    private String tipoDocumento;

    @XmlElement(name = "NumeroDocumento")
    private String numeroDocumento;

    @XmlElement(name = "NombreInstitucion")
    private String nombreInstitucion;

    @XmlElement(name = "FechaEmision")
    private LocalDateTime fechaEmision;

    @XmlElement(name = "PorcentajeExoneracion")
    private BigDecimal porcentajeExoneracion;

    @XmlElement(name = "MontoExoneracion")
    private BigDecimal montoExoneracion;

    public Exoneracion() {}

    public Exoneracion(Models.Detalles.Exoneracion src) {
        if (src != null) {
            this.tipoDocumento = src.getTipoDocumentoEX1();
            this.numeroDocumento = src.getNumeroDocumento();
            this.nombreInstitucion = src.getNombreInstitucion();
            this.fechaEmision = src.getFechaEmisionEX();
            this.porcentajeExoneracion = src.getTarifaExonerada();
            this.montoExoneracion = src.getMontoExoneracion();
        }
    }
}
