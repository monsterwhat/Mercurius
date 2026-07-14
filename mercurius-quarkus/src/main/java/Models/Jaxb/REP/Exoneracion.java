package Models.Jaxb.REP;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class Exoneracion {
    @XmlElement(name = "TipoDocumentoEX1")
    private String tipoDocumentoEX1;

    @XmlElement(name = "TipoDocumentoOtro")
    private String tipoDocumentoOTRO;

    @XmlElement(name = "NumeroDocumento")
    private String numeroDocumento;

    @XmlElement(name = "Articulo")
    private BigDecimal articulo;

    @XmlElement(name = "Inciso")
    private BigDecimal inciso;

    @XmlElement(name = "NombreInstitucion")
    private String nombreInstitucion;

    @XmlElement(name = "NombreInstitucionOtros")
    private String nombreInstitucionOtros;

    @XmlElement(name = "FechaEmisionEx")
    private LocalDateTime fechaEmisionEX;

    @XmlElement(name = "TarifaExonerada")
    private BigDecimal tarifaExonerada;

    @XmlElement(name = "MontoExoneracion")
    private BigDecimal montoExoneracion;

    public Exoneracion() {}

    public Exoneracion(Models.Detalles.Exoneracion src) {
        if (src != null) {
            this.tipoDocumentoEX1 = src.getTipoDocumentoEX1();
            this.tipoDocumentoOTRO = src.getTipoDocumentoOTRO();
            this.numeroDocumento = src.getNumeroDocumento();
            this.articulo = src.getArticulo();
            this.inciso = src.getInciso();
            this.nombreInstitucion = src.getNombreInstitucion();
            this.nombreInstitucionOtros = src.getNombreInstitucionOtros();
            this.fechaEmisionEX = src.getFechaEmisionEX();
            this.tarifaExonerada = src.getTarifaExonerada();
            this.montoExoneracion = src.getMontoExoneracion();
        }
    }
}
