package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * DTO for Models.PuntosTransaccion (registro de transacciones de puntos de lealtad).
 * Mirrors entity types; the cliente relation is flattened to id + display string:
 * cliente -> (clienteCode, clienteNombre).
 * Serves views under META-INF/resources/secured/pages/Loyalty/**.
 */
public class PuntosTransaccionDTO {

    @Nullable private Date fechaCreacion; // Fecha de la transacción
    @Nullable private String tipoTransaccion; // 'earn', 'redeem', 'expire'
    @Nullable private BigDecimal puntos; // Puntos movidos en la transacción
    @Nullable private BigDecimal saldoPuntos; // Saldo de puntos tras la transacción
    @Nullable private String descripcion; // Descripción de la transacción
    @Nullable private String facturaId; // Identificador de la factura asociada
    private int clienteCode; // Flattened: PuntosTransaccion.cliente.code
    private String clienteNombre; // Flattened: PuntosTransaccion.cliente.name

    public PuntosTransaccionDTO() {
    }

    public PuntosTransaccionDTO(@Nullable Date fechaCreacion, @Nullable String tipoTransaccion,
                                @Nullable BigDecimal puntos, @Nullable BigDecimal saldoPuntos,
                                @Nullable String descripcion, @Nullable String facturaId,
                                int clienteCode, String clienteNombre) {
        this.fechaCreacion = fechaCreacion;
        this.tipoTransaccion = tipoTransaccion;
        this.puntos = puntos;
        this.saldoPuntos = saldoPuntos;
        this.descripcion = descripcion;
        this.facturaId = facturaId;
        this.clienteCode = clienteCode;
        this.clienteNombre = clienteNombre;
    }

    @Nullable
    public Date getFechaCreacion() { return fechaCreacion; }
    public void setFechaCreacion(@Nullable Date fechaCreacion) { this.fechaCreacion = fechaCreacion; }

    @Nullable
    public String getTipoTransaccion() { return tipoTransaccion; }
    public void setTipoTransaccion(@Nullable String tipoTransaccion) { this.tipoTransaccion = tipoTransaccion; }

    @Nullable
    public BigDecimal getPuntos() { return puntos; }
    public void setPuntos(@Nullable BigDecimal puntos) { this.puntos = puntos; }

    @Nullable
    public BigDecimal getSaldoPuntos() { return saldoPuntos; }
    public void setSaldoPuntos(@Nullable BigDecimal saldoPuntos) { this.saldoPuntos = saldoPuntos; }

    @Nullable
    public String getDescripcion() { return descripcion; }
    public void setDescripcion(@Nullable String descripcion) { this.descripcion = descripcion; }

    @Nullable
    public String getFacturaId() { return facturaId; }
    public void setFacturaId(@Nullable String facturaId) { this.facturaId = facturaId; }

    public int getClienteCode() { return clienteCode; }
    public void setClienteCode(int clienteCode) { this.clienteCode = clienteCode; }

    public String getClienteNombre() { return clienteNombre; }
    public void setClienteNombre(String clienteNombre) { this.clienteNombre = clienteNombre; }
}
