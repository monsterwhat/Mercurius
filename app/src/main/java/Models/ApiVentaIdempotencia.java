package Models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;

/**
 * Clave de idempotencia de una venta creada por el API público.
 *
 * <p>Se reserva ANTES de facturar (estado pendiente) y se completa con el
 * comprobante al terminar. Un reintento con la misma clave devuelve el mismo
 * comprobante en vez de emitir otro; si el primero sigue en curso, responde
 * 409 en lugar de duplicar.</p>
 */
@Entity
@Table(name = "api_venta_idempotencia",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_api_venta_idem",
                columnNames = {"api_client_id", "clave"}),
        indexes = @Index(name = "idx_api_venta_idem_cliente", columnList = "api_client_id"))
public class ApiVentaIdempotencia {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "api_client_id", nullable = false, length = 120)
    private String apiClientId;

    @Column(name = "clave", nullable = false, length = 120)
    private String clave;

    @Column(name = "comprobante_id")
    private Long comprobanteId;

    @Column(name = "creado_en", nullable = false)
    private LocalDateTime creadoEn;

    @Column(name = "actualizado_en")
    private LocalDateTime actualizadoEn;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getApiClientId() {
        return apiClientId;
    }

    public void setApiClientId(String apiClientId) {
        this.apiClientId = apiClientId;
    }

    public String getClave() {
        return clave;
    }

    public void setClave(String clave) {
        this.clave = clave;
    }

    public Long getComprobanteId() {
        return comprobanteId;
    }

    public void setComprobanteId(Long comprobanteId) {
        this.comprobanteId = comprobanteId;
    }

    public LocalDateTime getCreadoEn() {
        return creadoEn;
    }

    public void setCreadoEn(LocalDateTime creadoEn) {
        this.creadoEn = creadoEn;
    }

    public LocalDateTime getActualizadoEn() {
        return actualizadoEn;
    }

    public void setActualizadoEn(LocalDateTime actualizadoEn) {
        this.actualizadoEn = actualizadoEn;
    }
}
