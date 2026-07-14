package Models.Jaxb.FE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlSchemaType;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import java.time.LocalDateTime;
import Models.Jaxb.LocalDateTimeAdapter;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class InformacionReferencia {
    @XmlElement(name = "TipoDocIR")
    private String tipoDoc;

    @XmlElement(name = "Numero")
    private String numero;

    @XmlElement(name = "FechaEmisionIR")
    @XmlSchemaType(name = "dateTime")
    @XmlJavaTypeAdapter(LocalDateTimeAdapter.class)
    private LocalDateTime fechaEmision;

    @XmlElement(name = "Codigo")
    private String codigo;

    @XmlElement(name = "Razon")
    private String razon;

    @XmlElement(name = "TipoDocRefOTRO")
    private String tipoDocRefOTRO;

    @XmlElement(name = "CodigoReferenciaOTRO")
    private String codigoReferenciaOTRO;

    public InformacionReferencia() {}

    public InformacionReferencia(Models.Referencias.InformacionReferencia src) {
        if (src != null) {
            this.tipoDoc = src.getTipoDoc();
            this.numero = src.getNumero();
            this.fechaEmision = src.getFechaEmision();
            this.codigo = src.getCodigo();
            this.razon = src.getRazon();
            this.tipoDocRefOTRO = src.getTipoDocRefOTRO();
            this.codigoReferenciaOTRO = src.getCodigoReferenciaOTRO();
        }
    }
}
