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
public class Encabezado {
    @XmlElement(name = "Clave")
    private String clave;

    @XmlElement(name = "ProveedorSistemas")
    private String proveedorSistemas;

    @XmlElement(name = "CodigoActividadEmisor")
    private String codigoActividadEmisor;

    @XmlElement(name = "CodigoActividadReceptor")
    private String codigoActividadReceptor;

    @XmlElement(name = "NumeroConsecutivo")
    private String numeroConsecutivo;

    @XmlElement(name = "FechaEmision")
    @XmlSchemaType(name = "dateTime")
    @XmlJavaTypeAdapter(LocalDateTimeAdapter.class)
    private LocalDateTime fechaEmision;

    @XmlElement(name = "Emisor")
    private Emisor emisor;

    @XmlElement(name = "Receptor")
    private Receptor receptor;

    @XmlElement(name = "CondicionVenta")
    private String condicionVenta;

    @XmlElement(name = "CondicionVentaOtros")
    private String condicionVentaOtros;

    @XmlElement(name = "PlazoCredito")
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
