package Models.Jaxb;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.XmlValue;
import jakarta.annotation.Nullable;
import java.util.List;

/**
 * JAXB model for the optional {@code <Otros>} element in Hacienda v4.4 documents.
 * <p>
 * Per the XSD, Otros is an extensibility container with {@code <OtroTexto>} and
 * {@code <OtroContenido>} child elements, each carrying an optional {@code codigo}
 * attribute and text content.
 * </p>
 * <p>This element appears in FE, FEC, FEE, NC, ND, TE but NOT in REP.</p>
 *
 * <h3>XML structure:</h3>
 * <pre>{@code
 * <Otros>
 *   <OtroTexto codigo="...">text</OtroTexto>
 *   <OtroContenido codigo="...">text</OtroContenido>
 * </Otros>
 * }</pre>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "Otros", propOrder = {"otroTexto", "otroContenido"})
public class Otros {

    @Nullable
    @XmlElement(name = "OtroTexto")
    private List<OtroTexto> otroTexto;

    @Nullable
    @XmlElement(name = "OtroContenido")
    private List<OtroContenido> otroContenido;

    public Otros() {}

    // ── Nested types ──────────────────────────────────────────────────────────

    /**
     * {@code <OtroTexto codigo="...">text</OtroTexto>}
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "OtroTexto")
    public static class OtroTexto {
        @XmlValue
        private String value;
        @Nullable
        @XmlAttribute(name = "codigo")
        private String codigo;

        public OtroTexto() {}
    }

    /**
     * {@code <OtroContenido codigo="...">text</OtroContenido>}
     */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "OtroContenido")
    public static class OtroContenido {
        @XmlValue
        private String value;
        @Nullable
        @XmlAttribute(name = "codigo")
        private String codigo;

        public OtroContenido() {}
    }

    // ── Getters ───────────────────────────────────────────────────────────────

    @Nullable
    public List<OtroTexto> getOtroTexto() { return otroTexto; }
    public void setOtroTexto(@Nullable List<OtroTexto> otroTexto) { this.otroTexto = otroTexto; }

    @Nullable
    public List<OtroContenido> getOtroContenido() { return otroContenido; }
    public void setOtroContenido(@Nullable List<OtroContenido> otroContenido) { this.otroContenido = otroContenido; }
}
