package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Lote (batch) row for lotes tables and expiry reports.
 * Mirrors the scalar fields of Models.Lote.
 * Relations are flattened: articulo -> articuloCodigo + articuloNombre,
 * usuario -> usuarioId + usuarioUsername.
 * Nested entities (Articulos, Users) are intentionally excluded.
 */
public class LoteDTO {

    private Long id; // Lote.id
    private Long articuloCodigo; // Lote.articulo.codigo (join columna articulo_codigo)
    private String articuloNombre; // Lote.articulo.nombre
    private String numeroLote; // Lote.numeroLote
    private Date fechaVencimiento; // Lote.fechaVencimiento
    private BigDecimal cantidadInicial; // Lote.cantidadInicial
    private BigDecimal cantidadActual; // Lote.cantidadActual
    private Date fechaIngreso; // Lote.fechaIngreso

    // ---- Relaciones aplanadas ----
    @Nullable private Long usuarioId; // Lote.usuario.id
    @Nullable private String usuarioUsername; // Lote.usuario.username

    @Nullable private String notas; // Lote.notas
    @Nullable private Boolean status; // Lote.status (chip activo/inactivo)

    public LoteDTO() {}

    public LoteDTO(Long id, Long articuloCodigo, String articuloNombre, String numeroLote,
                   Date fechaVencimiento, BigDecimal cantidadInicial, BigDecimal cantidadActual,
                   Date fechaIngreso, @Nullable Long usuarioId, @Nullable String usuarioUsername,
                   @Nullable String notas, @Nullable Boolean status) {
        this.id = id;
        this.articuloCodigo = articuloCodigo;
        this.articuloNombre = articuloNombre;
        this.numeroLote = numeroLote;
        this.fechaVencimiento = fechaVencimiento;
        this.cantidadInicial = cantidadInicial;
        this.cantidadActual = cantidadActual;
        this.fechaIngreso = fechaIngreso;
        this.usuarioId = usuarioId;
        this.usuarioUsername = usuarioUsername;
        this.notas = notas;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getArticuloCodigo() { return articuloCodigo; }
    public void setArticuloCodigo(Long articuloCodigo) { this.articuloCodigo = articuloCodigo; }

    public String getArticuloNombre() { return articuloNombre; }
    public void setArticuloNombre(String articuloNombre) { this.articuloNombre = articuloNombre; }

    public String getNumeroLote() { return numeroLote; }
    public void setNumeroLote(String numeroLote) { this.numeroLote = numeroLote; }

    public Date getFechaVencimiento() { return fechaVencimiento; }
    public void setFechaVencimiento(Date fechaVencimiento) { this.fechaVencimiento = fechaVencimiento; }

    public BigDecimal getCantidadInicial() { return cantidadInicial; }
    public void setCantidadInicial(BigDecimal cantidadInicial) { this.cantidadInicial = cantidadInicial; }

    public BigDecimal getCantidadActual() { return cantidadActual; }
    public void setCantidadActual(BigDecimal cantidadActual) { this.cantidadActual = cantidadActual; }

    public Date getFechaIngreso() { return fechaIngreso; }
    public void setFechaIngreso(Date fechaIngreso) { this.fechaIngreso = fechaIngreso; }

    @Nullable
    public Long getUsuarioId() { return usuarioId; }
    public void setUsuarioId(@Nullable Long usuarioId) { this.usuarioId = usuarioId; }

    @Nullable
    public String getUsuarioUsername() { return usuarioUsername; }
    public void setUsuarioUsername(@Nullable String usuarioUsername) { this.usuarioUsername = usuarioUsername; }

    @Nullable
    public String getNotas() { return notas; }
    public void setNotas(@Nullable String notas) { this.notas = notas; }

    @Nullable
    public Boolean getStatus() { return status; }
    public void setStatus(@Nullable Boolean status) { this.status = status; }
}
