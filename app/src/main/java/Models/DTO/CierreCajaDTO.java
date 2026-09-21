package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Cierre de caja view for caja reports and dashboards.
 * Mirrors the scalar fields of Models.CierreCaja.
 * Relations are flattened: usuario -> usuarioId + usuarioUsername.
 * Nested entities (Users) are intentionally excluded.
 */
public class CierreCajaDTO {

    private Long id; // CierreCaja.id
    private Long usuarioId; // CierreCaja.usuario.id (join columna usuario_id, NOT NULL)
    private String usuarioUsername; // CierreCaja.usuario.username
    private Date fechaApertura; // CierreCaja.fechaApertura
    @Nullable private Date fechaCierre; // CierreCaja.fechaCierre (nulo mientras estado = "abierto")
    private BigDecimal montoInicial; // CierreCaja.montoInicial
    @Nullable private BigDecimal montoEsperadoEfectivo; // CierreCaja.montoEsperadoEfectivo
    @Nullable private BigDecimal montoEsperadoSinpe; // CierreCaja.montoEsperadoSinpe
    @Nullable private BigDecimal montoEsperadoTarjeta; // CierreCaja.montoEsperadoTarjeta
    @Nullable private BigDecimal montoContadoEfectivo; // CierreCaja.montoContadoEfectivo
    @Nullable private BigDecimal montoContadoSinpe; // CierreCaja.montoContadoSinpe
    @Nullable private BigDecimal montoContadoTarjeta; // CierreCaja.montoContadoTarjeta
    @Nullable private BigDecimal diferencia; // CierreCaja.diferencia
    private String estado; // CierreCaja.estado ("abierto" or "cerrado")
    @Nullable private String notas; // CierreCaja.notas

    public CierreCajaDTO() {}

    public CierreCajaDTO(Long id, Long usuarioId, String usuarioUsername, Date fechaApertura,
                         @Nullable Date fechaCierre, BigDecimal montoInicial,
                         @Nullable BigDecimal montoEsperadoEfectivo, @Nullable BigDecimal montoEsperadoSinpe,
                         @Nullable BigDecimal montoEsperadoTarjeta, @Nullable BigDecimal montoContadoEfectivo,
                         @Nullable BigDecimal montoContadoSinpe, @Nullable BigDecimal montoContadoTarjeta,
                         @Nullable BigDecimal diferencia, String estado, @Nullable String notas) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.usuarioUsername = usuarioUsername;
        this.fechaApertura = fechaApertura;
        this.fechaCierre = fechaCierre;
        this.montoInicial = montoInicial;
        this.montoEsperadoEfectivo = montoEsperadoEfectivo;
        this.montoEsperadoSinpe = montoEsperadoSinpe;
        this.montoEsperadoTarjeta = montoEsperadoTarjeta;
        this.montoContadoEfectivo = montoContadoEfectivo;
        this.montoContadoSinpe = montoContadoSinpe;
        this.montoContadoTarjeta = montoContadoTarjeta;
        this.diferencia = diferencia;
        this.estado = estado;
        this.notas = notas;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getUsuarioId() { return usuarioId; }
    public void setUsuarioId(Long usuarioId) { this.usuarioId = usuarioId; }

    public String getUsuarioUsername() { return usuarioUsername; }
    public void setUsuarioUsername(String usuarioUsername) { this.usuarioUsername = usuarioUsername; }

    public Date getFechaApertura() { return fechaApertura; }
    public void setFechaApertura(Date fechaApertura) { this.fechaApertura = fechaApertura; }

    @Nullable
    public Date getFechaCierre() { return fechaCierre; }
    public void setFechaCierre(@Nullable Date fechaCierre) { this.fechaCierre = fechaCierre; }

    public BigDecimal getMontoInicial() { return montoInicial; }
    public void setMontoInicial(BigDecimal montoInicial) { this.montoInicial = montoInicial; }

    @Nullable
    public BigDecimal getMontoEsperadoEfectivo() { return montoEsperadoEfectivo; }
    public void setMontoEsperadoEfectivo(@Nullable BigDecimal montoEsperadoEfectivo) { this.montoEsperadoEfectivo = montoEsperadoEfectivo; }

    @Nullable
    public BigDecimal getMontoEsperadoSinpe() { return montoEsperadoSinpe; }
    public void setMontoEsperadoSinpe(@Nullable BigDecimal montoEsperadoSinpe) { this.montoEsperadoSinpe = montoEsperadoSinpe; }

    @Nullable
    public BigDecimal getMontoEsperadoTarjeta() { return montoEsperadoTarjeta; }
    public void setMontoEsperadoTarjeta(@Nullable BigDecimal montoEsperadoTarjeta) { this.montoEsperadoTarjeta = montoEsperadoTarjeta; }

    @Nullable
    public BigDecimal getMontoContadoEfectivo() { return montoContadoEfectivo; }
    public void setMontoContadoEfectivo(@Nullable BigDecimal montoContadoEfectivo) { this.montoContadoEfectivo = montoContadoEfectivo; }

    @Nullable
    public BigDecimal getMontoContadoSinpe() { return montoContadoSinpe; }
    public void setMontoContadoSinpe(@Nullable BigDecimal montoContadoSinpe) { this.montoContadoSinpe = montoContadoSinpe; }

    @Nullable
    public BigDecimal getMontoContadoTarjeta() { return montoContadoTarjeta; }
    public void setMontoContadoTarjeta(@Nullable BigDecimal montoContadoTarjeta) { this.montoContadoTarjeta = montoContadoTarjeta; }

    @Nullable
    public BigDecimal getDiferencia() { return diferencia; }
    public void setDiferencia(@Nullable BigDecimal diferencia) { this.diferencia = diferencia; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    @Nullable
    public String getNotas() { return notas; }
    public void setNotas(@Nullable String notas) { this.notas = notas; }
}
