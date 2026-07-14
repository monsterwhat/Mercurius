package Models.Jaxb.FEE;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@Data
public class Emisor {
    @XmlElement(name = "Nombre")
    private String nombre;

    @XmlElement(name = "Identificacion")
    private IdentificacionEmisor identificacion;

    @XmlElement(name = "Registrofiscal8707")
    private String Registrofiscal8707;

    @XmlElement(name = "NombreComercial")
    private String nombreComercial;

    @XmlElement(name = "Ubicacion")
    private Ubicacion ubicacion;

    @XmlElement(name = "Telefono")
    private Telefono telefono;

    @XmlElement(name = "CorreoElectronico")
    private List<CorreoElectronicoEmisor> correosElectronicos;

    public Emisor() {}

    public Emisor(Models.Encabezado.Emisor src) {
        if (src != null) {
            this.nombre = src.getNombre();
            this.Registrofiscal8707 = src.getRegistrofiscal8707();
            this.nombreComercial = src.getNombreComercial();
            if (src.getIdentificacion() != null)
                this.identificacion = new IdentificacionEmisor(src.getIdentificacion());
            if (src.getUbicacion() != null)
                this.ubicacion = new Ubicacion(src.getUbicacion());
            if (src.getTelefono() != null)
                this.telefono = new Telefono(src.getTelefono());
            if (src.getCorreosElectronicos() != null)
                this.correosElectronicos = src.getCorreosElectronicos().stream()
                    .map(CorreoElectronicoEmisor::new).collect(Collectors.toList());
        }
    }
}
