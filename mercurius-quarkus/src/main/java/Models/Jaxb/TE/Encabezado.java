package Models.Jaxb.TE;

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
public class Encabezado {
    @XmlElement(name = "Clave", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private String clave;

    @XmlElement(name = "ProveedorSistemas", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private String proveedorSistemas;

    @XmlElement(name = "CodigoActividadEmisor", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private String codigoActividadEmisor;

    @XmlElement(name = "CodigoActividadReceptor", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private String codigoActividadReceptor;

    @XmlElement(name = "NumeroConsecutivo", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private String numeroConsecutivo;

    @XmlElement(name = "FechaEmision", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    @XmlSchemaType(name = "dateTime")
    @XmlJavaTypeAdapter(LocalDateTimeAdapter.class)
    private LocalDateTime fechaEmision;

    @XmlElement(name = "Emisor", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private Emisor emisor;

    @XmlElement(name = "Receptor", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private Receptor receptor;

    @XmlElement(name = "CondicionVenta", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private String condicionVenta;

    @XmlElement(name = "CondicionVentaOtros", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private String condicionVentaOtros;

    @XmlElement(name = "PlazoCredito", namespace = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico")
    private String plazoCredito;

    public Encabezado() {}

    public Encabezado(Models.Encabezado.Encabezado src) {
        if (src != null) {
            this.clave = src.getClave();
            this.proveedorSistemas = src.getProveedorSistemas();
            this.codigoActividadEmisor = src.getCodigoActividadEmisor();
            this.codigoActividadReceptor = src.getCodigoActividadReceptor();
            this.numeroConsecutivo = src.getNumeroConsecutivo();
            this.fechaEmision = src.getFechaEmision();
            this.condicionVenta = src.getCondicionVenta();
            this.condicionVentaOtros = src.getCondicionVentaOtros();
            this.plazoCredito = src.getPlazoCredito();
            if (src.getEmisor() != null)
                this.emisor = new Emisor(src.getEmisor());
            if (src.getReceptor() != null)
                this.receptor = new Receptor(src.getReceptor());
        }
    }
}
