package Models.Jaxb.REP;

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
 * JAXB root for ReciboElectronicoPago (REP).
 * <p>
 * Uses REP wrapper types from {@link Models.Jaxb.REP} for proper namespace
 * qualification - namespace is inherited from {@code package-info.java}.
 * </p>
 */
@XmlRootElement(name = "ReciboElectronicoPago")
@XmlAccessorType(XmlAccessType.FIELD)
@Data
@XmlType(name = "ReciboElectronicoPago", propOrder = {
    "encabezado",
    "detalleServicio",
    "resumen",
    "informacionReferencia"
})
public class ReciboElectronicoPagoDocumento {

    @Nonnull
    @XmlElement(name = "Encabezado")
    private Encabezado encabezado;

    @Nonnull
    @XmlElement(name = "DetalleServicio")
    private DetalleServicio detalleServicio;

    @Nonnull
    @XmlElement(name = "ResumenFactura")
    private ResumenFactura resumen;

    @Nullable
    @XmlElement(name = "InformacionReferencia")
    private List<InformacionReferencia> informacionReferencia;

    // JAXB requires no-arg constructor
    public ReciboElectronicoPagoDocumento() {}

    /**
     * Constructs from a {@link ComprobantesEmitidos} entity, converting
     * each base entity to its corresponding REP wrapper type.
     */
    public ReciboElectronicoPagoDocumento(ComprobantesEmitidos ce) {
        this.encabezado = new Encabezado(ce.getEncabezado());
        this.detalleServicio = new DetalleServicio(ce.getDetalles());

        if (ce.getInformacionReferencia() != null && !ce.getInformacionReferencia().isEmpty()) {
            this.informacionReferencia = ce.getInformacionReferencia().stream()
                .map(InformacionReferencia::new).collect(Collectors.toList());
        }
        this.resumen = new ResumenFactura(ce.getResumen());
    }
}
