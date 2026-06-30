package Models.Documentos;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlSchemaType;
import jakarta.xml.bind.annotation.XmlType;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * JAXB wrapper for MensajeReceptor (acceptance/rejection of received electronic documents).
 * Maps to MensajeReceptor_V4.4.xsd — {@code <MensajeReceptor>} root element.
 *
 * Mensaje codes: 1=Aceptado, 2=AceptadoParcialmente, 3=Rechazado
 */
@XmlRootElement(name = "MensajeReceptor",
                namespace = "https://tribunet.hacienda.go.cr/docs/esquemas/2017/v4.4/mensajeReceptor")
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "MensajeReceptor", propOrder = {
    "clave",
    "numeroCedulaEmisor",
    "fechaEmisionDoc",
    "mensaje",
    "detalleMensaje",
    "montoTotalImpuesto",
    "codigoActividad",
    "condicionImpuesto",
    "montoTotalImpuestoAcreditar",
    "montoTotalDeGastoAplicable",
    "totalFactura",
    "numeroCedulaReceptor",
    "numeroConsecutivoReceptor"
})
public class MensajeReceptorDocumento {

    @XmlElement(name = "Clave", required = true)
    private String clave;

    @XmlElement(name = "NumeroCedulaEmisor", required = true)
    private String numeroCedulaEmisor;

    @XmlElement(name = "FechaEmisionDoc", required = true)
    @XmlSchemaType(name = "dateTime")
    private LocalDateTime fechaEmisionDoc;

    @XmlElement(name = "Mensaje", required = true)
    private Integer mensaje;

    @XmlElement(name = "DetalleMensaje")
    private String detalleMensaje;

    @XmlElement(name = "MontoTotalImpuesto")
    private BigDecimal montoTotalImpuesto;

    @XmlElement(name = "CodigoActividad")
    private String codigoActividad;

    @XmlElement(name = "CondicionImpuesto")
    private String condicionImpuesto;

    @XmlElement(name = "MontoTotalImpuestoAcreditar")
    private BigDecimal montoTotalImpuestoAcreditar;

    @XmlElement(name = "MontoTotalDeGastoAplicable")
    private BigDecimal montoTotalDeGastoAplicable;

    @XmlElement(name = "TotalFactura", required = true)
    private BigDecimal totalFactura;

    @XmlElement(name = "NumeroCedulaReceptor", required = true)
    private String numeroCedulaReceptor;

    @XmlElement(name = "NumeroConsecutivoReceptor", required = true)
    private String numeroConsecutivoReceptor;

    public MensajeReceptorDocumento() {}

    // ── Getters and Setters ──────────────────────────────────────────────

    public String getClave() { return clave; }
    public void setClave(String clave) { this.clave = clave; }

    public String getNumeroCedulaEmisor() { return numeroCedulaEmisor; }
    public void setNumeroCedulaEmisor(String numeroCedulaEmisor) { this.numeroCedulaEmisor = numeroCedulaEmisor; }

    public LocalDateTime getFechaEmisionDoc() { return fechaEmisionDoc; }
    public void setFechaEmisionDoc(LocalDateTime fechaEmisionDoc) { this.fechaEmisionDoc = fechaEmisionDoc; }

    public Integer getMensaje() { return mensaje; }
    public void setMensaje(Integer mensaje) { this.mensaje = mensaje; }

    public String getDetalleMensaje() { return detalleMensaje; }
    public void setDetalleMensaje(String detalleMensaje) { this.detalleMensaje = detalleMensaje; }

    public BigDecimal getMontoTotalImpuesto() { return montoTotalImpuesto; }
    public void setMontoTotalImpuesto(BigDecimal montoTotalImpuesto) { this.montoTotalImpuesto = montoTotalImpuesto; }

    public String getCodigoActividad() { return codigoActividad; }
    public void setCodigoActividad(String codigoActividad) { this.codigoActividad = codigoActividad; }

    public String getCondicionImpuesto() { return condicionImpuesto; }
    public void setCondicionImpuesto(String condicionImpuesto) { this.condicionImpuesto = condicionImpuesto; }

    public BigDecimal getMontoTotalImpuestoAcreditar() { return montoTotalImpuestoAcreditar; }
    public void setMontoTotalImpuestoAcreditar(BigDecimal montoTotalImpuestoAcreditar) { this.montoTotalImpuestoAcreditar = montoTotalImpuestoAcreditar; }

    public BigDecimal getMontoTotalDeGastoAplicable() { return montoTotalDeGastoAplicable; }
    public void setMontoTotalDeGastoAplicable(BigDecimal montoTotalDeGastoAplicable) { this.montoTotalDeGastoAplicable = montoTotalDeGastoAplicable; }

    public BigDecimal getTotalFactura() { return totalFactura; }
    public void setTotalFactura(BigDecimal totalFactura) { this.totalFactura = totalFactura; }

    public String getNumeroCedulaReceptor() { return numeroCedulaReceptor; }
    public void setNumeroCedulaReceptor(String numeroCedulaReceptor) { this.numeroCedulaReceptor = numeroCedulaReceptor; }

    public String getNumeroConsecutivoReceptor() { return numeroConsecutivoReceptor; }
    public void setNumeroConsecutivoReceptor(String numeroConsecutivoReceptor) { this.numeroConsecutivoReceptor = numeroConsecutivoReceptor; }
}
