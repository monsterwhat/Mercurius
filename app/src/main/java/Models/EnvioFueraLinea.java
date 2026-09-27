package Models;

import jakarta.annotation.Nullable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * Durable outbox row for an electronic document that was signed at the point of
 * sale but could not be transmitted to Hacienda.
 *
 * <h3>Why this entity exists — CR Art. 21 ¶3 (Ley 6828, Ley de la Hacienda)</h3>
 * When the signed XML cannot be sent for lack of connectivity, the document must
 * be <em>generated and signed at the moment of the sale</em> and transmitted
 * <em>no later than two business days</em> afterwards, with
 * {@code Situacion = 3} in the document key (position 42 of the 50-character
 * clave). The signature produced at the sale is the one Hacienda must receive:
 * re-marshalling and re-signing the document on retry would produce a different
 * signed payload for the same clave, so the exact bytes are persisted here and
 * replayed verbatim.
 *
 * <h3>Why the whole envelope is duplicated</h3>
 * The row is deliberately <em>self-sufficient</em>: the retry path must be able
 * to submit the document without re-reading (or depending on the continued
 * existence of) the {@link ComprobantesEmitidos} graph. The
 * emisor/receptor identification segments are frozen at signing time because
 * they are part of the signed XML — reading them back from a reconfigured
 * {@link ConfiguracionAplicacion} at retry time could produce an envelope that
 * contradicts the signature.
 *
 * <h3>Schema</h3>
 * Created automatically by {@code quarkus.hibernate-orm.schema-management.strategy=update};
 * no Flyway/Liquibase migration is required. A single-row-per-clave unique
 * constraint makes {@code registrarDocumentoFirmado} idempotent even if the sale
 * path is retried.
 */
@Data
@Entity
@Table(name = "envio_fuera_linea",
        uniqueConstraints = @UniqueConstraint(columnNames = {"clave"}),
        indexes = @Index(name = "idx_envio_fuera_linea_reintento",
                columnList = "estado,proximo_intento"))
public class EnvioFueraLinea {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ── Document identity (all strings, exactly as emitted) ────────────────

    /** Hacienda document type: "01" FE, "04" TE, "02" NC, "03" ND, "05" FEE, "08" FEC, "10" REP. */
    @Column(name = "tipo_documento", length = 2, nullable = false)
    private String tipoDocumento;

    /** 50-character document key, Situacion included at position 42. Unique. */
    @Column(name = "clave", length = 50, nullable = false)
    private String clave;

    /** 20-digit NumeroConsecutivo (sucursal + terminal + tipo + secuencial). */
    @Column(name = "consecutivo", length = 20, nullable = false)
    private String consecutivo;

    @Column(name = "sucursal", length = 3, nullable = false)
    private String sucursal;

    @Column(name = "terminal", length = 5, nullable = false)
    private String terminal;

    /** Situacion stamped into the clave: "1" normal, "3" fuera de línea (Art. 21 ¶3). */
    @Column(name = "situacion", length = 1, nullable = false)
    private String situacion;

    /** FK-shaped pointer to ComprobantesEmitidos.id; nullable only if that row was hard-deleted. */
    @Nullable
    @Column(name = "comprobante_id")
    private Long comprobanteId;

    // ── Frozen transmission envelope (must match the signature) ────────────

    @Nullable
    @Column(name = "emisor_tipo_id", length = 2)
    private String emisorTipoId;

    @Nullable
    @Column(name = "emisor_numero_id", length = 20)
    private String emisorNumeroId;

    @Nullable
    @Column(name = "receptor_tipo_id", length = 2)
    private String receptorTipoId;

    @Nullable
    @Column(name = "receptor_numero_id", length = 20)
    private String receptorNumeroId;

    // ── The signed payload ────────────────────────────────────────────────

    /**
     * Human-auditable copy of the XAdES-signed XML, byte-identical in content to
     * {@link #xmlFirmadoBytes}. Kept as text so an auditor can read the document
     * straight out of the database.
     */
    @Lob
    @Nullable
    @Column(name = "xml_firmado")
    private String xmlFirmado;

    /**
     * Exact UTF-8 bytes to hand to {@code POST /recepcion}. Authoritative for
     * transmission: a signature covers bytes, not a Java String, and any
     * re-encoding on the way out could invalidate it.
     */
    @Lob
    @Nullable
    @Column(name = "xml_firmado_bytes")
    private byte[] xmlFirmadoBytes;

    // ── Retry bookkeeping ─────────────────────────────────────────────────

    /** How many transmission attempts have been made (0 = never attempted). */
    @Column(name = "intentos", nullable = false)
    private Integer intentos = 0;

    /** Earliest time the next attempt may run; null means "as soon as possible". */
    @Nullable
    @Column(name = "proximo_intento")
    private LocalDateTime proximoIntento;

    /** PENDIENTE, ENVIADO, RECHAZADO or VENCIDO. Terminal states are never retried. */
    @Column(name = "estado", length = 20, nullable = false)
    private String estado;

    @Lob
    @Nullable
    @Column(name = "ultimo_error")
    private String ultimoError;

    /** Why the row exists: OFFLINE_SITUACION_3 or FALLO_ENVIO_INMEDIATO. */
    @Nullable
    @Column(name = "origen", length = 30)
    private String origen;

    // ── Bookkeeping timestamps ────────────────────────────────────────────

    @Nullable
    @Column(name = "fecha_emision")
    private LocalDateTime fechaEmision;

    @Nullable
    @Column(name = "fecha_creacion", nullable = false)
    private LocalDateTime fechaCreacion;

    @Nullable
    @Column(name = "fecha_ultimo_intento")
    private LocalDateTime fechaUltimoIntento;

    @Nullable
    @Column(name = "fecha_envio")
    private LocalDateTime fechaEnvio;

    /**
     * Hard Art. 21 ¶3 deadline: two business days after emission. Persisted (not
     * recomputed on read) so a config or clock change cannot silently extend a
     * legal deadline that was already communicated to the operator.
     */
    @Nullable
    @Column(name = "vencimiento")
    private LocalDateTime vencimiento;
}
