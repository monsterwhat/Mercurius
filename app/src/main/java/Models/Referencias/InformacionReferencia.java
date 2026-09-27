package Models.Referencias;

import Models.ComprobantesEmitidos;
import Models.Enums.Tipo_CodigosReferencia;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlTransient;
import java.time.LocalDateTime;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.Data;

@XmlAccessorType(XmlAccessType.FIELD)
@XmlRootElement(name = "InformacionReferencia")
@Data
@Entity
@Table(name = "informacion_referencia")
public class InformacionReferencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @XmlTransient
    private Long id;

    @Nullable
    @XmlElement(name = "TipoDocIR")
    @Column(name = "tipo_doc", length = 2)
    private String tipoDoc;

    @Nullable
    @XmlElement(name = "Numero")
    @Column(name = "numero", length = 50)
    private String numero;

    @Nullable
    @XmlElement(name = "FechaEmisionIR")
    @Column(name = "fecha_emision")
    private LocalDateTime fechaEmision;

    @Nullable
    @XmlElement(name = "Codigo")
    @Column(name = "codigo", length = 2)
    private String codigo;

    @Nullable
    @XmlElement(name = "Razon")
    @Column(name = "razon", length = 180)
    private String razon;

    @Nullable
    @XmlElement(name = "TipoDocRefOTRO")
    @Column(name = "tipo_doc_otro", length = 100)
    private String tipoDocRefOTRO;

    @Nullable
    @XmlElement(name = "CodigoReferenciaOTRO")
    @Column(name = "codigo_otro", length = 100)
    private String codigoReferenciaOTRO;

    /**
     * Creates an InformacionReferencia referencing an existing emitted comprobante.
     * Used when issuing NC/ND/REP that refer to a previously issued invoice.
     *
     * @param original            the original ComprobantesEmitidos being referenced
     * @param codigo              Nota 9 code to stamp on the reference
     * @param razon               human-readable explanation
     * @param codigoDocumento     CodigoDocumento of the comprobante being emitted, used to
     *                            reject codes the official schema does not accept there
     *                            (e.g. 17 outside the Recibo Electrónico de Pago)
     * @return a new InformacionReferencia ready to attach to the NC/ND/REP comprobante
     * @throws IllegalArgumentException if {@code codigo} is not legal for {@code codigoDocumento}
     */
    public static InformacionReferencia from(@Nonnull ComprobantesEmitidos original,
                                             @Nonnull Tipo_CodigosReferencia codigo,
                                             @Nonnull String razon,
                                             @Nullable String codigoDocumento) {
        Tipo_CodigosReferencia.validarParaDocumento(codigoDocumento, codigo);
        InformacionReferencia ref = new InformacionReferencia();
        if (original.getEncabezado() != null) {
            ref.setTipoDoc(original.getEncabezado().getCodigoDocumento());
            ref.setNumero(original.getEncabezado().getNumeroConsecutivo());
            ref.setFechaEmision(original.getEncabezado().getFechaEmision());
        }
        ref.setCodigo(codigo.getCodigo());
        ref.setRazon(razon);
        return ref;
    }
}