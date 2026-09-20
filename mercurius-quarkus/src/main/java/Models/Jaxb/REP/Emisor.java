package Models.Jaxb.REP;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(propOrder = {"nombre", "identificacion", "correosElectronicos", "Registrofiscal8707", "nombreComercial", "ubicacion", "telefono"})
@Data
public class Emisor {
    @XmlElement(name = "Nombre")
    private String nombre;

    @XmlElement(name = "Identificacion")
    private IdentificacionEmisor identificacion;

    @XmlElement(name = "CorreoElectronico")
    private List<CorreoElectronicoEmisor> correosElectronicos;

    @XmlElement(name = "Registrofiscal8707")
    private String Registrofiscal8707;

    @XmlElement(name = "NombreComercial")
    private String nombreComercial;

    @XmlElement(name = "Ubicacion")
    private Ubicacion ubicacion;

    @XmlElement(name = "Telefono")
    private Telefono telefono;

    public Emisor() {}

    public Emisor(Models.Encabezado.Emisor src) {
        if (src != null) {
            this.nombre = src.getNombre();
            if (src.getIdentificacion() != null)
                this.identificacion = new IdentificacionEmisor(src.getIdentificacion());
            if (src.getCorreosElectronicos() != null)
                this.correosElectronicos = src.getCorreosElectronicos().stream()
                    .map(CorreoElectronicoEmisor::new).collect(Collectors.toList());
            // REP minimal per XSD
            this.Registrofiscal8707 = null;
            this.nombreComercial = null;
            this.ubicacion = null;
            this.telefono = null;
        }
    }
}
