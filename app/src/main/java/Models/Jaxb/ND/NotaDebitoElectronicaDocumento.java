package Models.Jaxb.ND;

import Models.ComprobantesEmitidos;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;

/**
 * JAXB root for NotaDebitoElectronica (ND).
 * <p>
 * Uses ND wrapper types from {@link Models.Jaxb.ND} for proper namespace
 * qualification - namespace is inherited from {@code package-info.java}.
 * </p>
 */
@XmlRootElement(name = "NotaDebitoElectronica")
@XmlAccessorType(XmlAccessType.FIELD)
@Data
@XmlType(name = "NotaDebitoElectronica", propOrder = {
    "encabezado",
    "detalleServicio",
    "otrosCargos",
    "resumen",
    "informacionReferencia",
    "otros"
})
public class NotaDebitoElectronicaDocumento {

    @Nonnull
    @XmlElement(name = "Encabezado")
    private Encabezado encabezado;

    @Nonnull
    @XmlElement(name = "DetalleServicio")
    private DetalleServicio detalleServicio;

    @Nullable
    @XmlElement(name = "OtrosCargos")
    private List<OtroCargo> otrosCargos;

    @Nonnull
    @XmlElement(name = "ResumenFactura")
    private ResumenFactura resumen;

    @Nullable
    @XmlElement(name = "InformacionReferencia")
    private List<InformacionReferencia> informacionReferencia;

    @Nullable
    @XmlElement(name = "Otros")
    private Models.Jaxb.Otros otros;

    // JAXB requires no-arg constructor
    public NotaDebitoElectronicaDocumento() {}

    /**
     * Constructs from a {@link ComprobantesEmitidos} entity, converting
     * each base entity to its corresponding ND wrapper type.
     */
    public NotaDebitoElectronicaDocumento(ComprobantesEmitidos ce) {
        this.encabezado = new Encabezado(ce.getEncabezado());
        this.detalleServicio = new DetalleServicio(ce.getDetalles());        if (ce.getDetalles().getOtrosCargos() != null && !ce.getDetalles().getOtrosCargos().isEmpty()) {
            this.otrosCargos = ce.getDetalles().getOtrosCargos().stream()
                .map(OtroCargo::new).collect(Collectors.toList());
        }
        if (ce.getInformacionReferencia() != null && !ce.getInformacionReferencia().isEmpty()) {
            this.informacionReferencia = ce.getInformacionReferencia().stream()
                .map(InformacionReferencia::new).collect(Collectors.toList());
        }
        this.resumen = new ResumenFactura(ce.getResumen());
    }
}
