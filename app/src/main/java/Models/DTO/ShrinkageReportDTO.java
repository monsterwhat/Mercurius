package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Shrinkage (mermas y pérdidas) analysis report for the /api/app surface.
 * Mirrors the six public reads of Services.ShrinkageAnalysisService for one
 * date window:
 * <ul>
 *   <li>{@code totalMerma} ← getTotalShrinkage(start, end)</li>
 *   <li>{@code porcentajeMerma} ← getShrinkagePercentage(start, end)</li>
 *   <li>{@code movimientoTotal} ← getTotalInventoryMovement(start, end)</li>
 *   <li>{@code mermaPorCausa} ← getShrinkageByCause(start, end)</li>
 *   <li>{@code mermaPorDepartamento} ← getShrinkageByDepartment(start, end)</li>
 *   <li>{@code movimientos} ← getShrinkageMovements(start, end), relations
 *       flattened per the LoteDTO convention (articulo → articuloCodigo +
 *       articuloNombre, usuario → usuarioId + usuarioUsername; nested
 *       entities intentionally excluded)</li>
 * </ul>
 */
public class ShrinkageReportDTO {

    @Nonnull private Date fechaInicio; // analysis window start
    @Nonnull private Date fechaFin; // analysis window end

    @Nonnull private BigDecimal totalMerma; // ShrinkageAnalysisService.getTotalShrinkage
    @Nonnull private BigDecimal porcentajeMerma; // ShrinkageAnalysisService.getShrinkagePercentage
    @Nonnull private BigDecimal movimientoTotal; // ShrinkageAnalysisService.getTotalInventoryMovement

    @Nonnull private Map<String, BigDecimal> mermaPorCausa; // getShrinkageByCause (tipoMovimiento -> total)
    @Nonnull private Map<String, BigDecimal> mermaPorDepartamento; // getShrinkageByDepartment (departamento -> total)

    @Nonnull private List<MovimientoDTO> movimientos; // getShrinkageMovements (flattened)

    public ShrinkageReportDTO() {}

    public ShrinkageReportDTO(@Nonnull Date fechaInicio, @Nonnull Date fechaFin,
                              @Nonnull BigDecimal totalMerma, @Nonnull BigDecimal porcentajeMerma,
                              @Nonnull BigDecimal movimientoTotal,
                              @Nonnull Map<String, BigDecimal> mermaPorCausa,
                              @Nonnull Map<String, BigDecimal> mermaPorDepartamento,
                              @Nonnull List<MovimientoDTO> movimientos) {
        this.fechaInicio = fechaInicio;
        this.fechaFin = fechaFin;
        this.totalMerma = totalMerma;
        this.porcentajeMerma = porcentajeMerma;
        this.movimientoTotal = movimientoTotal;
        this.mermaPorCausa = mermaPorCausa;
        this.mermaPorDepartamento = mermaPorDepartamento;
        this.movimientos = movimientos;
    }

    @Nonnull
    public Date getFechaInicio() { return fechaInicio; }
    public void setFechaInicio(@Nonnull Date fechaInicio) { this.fechaInicio = fechaInicio; }

    @Nonnull
    public Date getFechaFin() { return fechaFin; }
    public void setFechaFin(@Nonnull Date fechaFin) { this.fechaFin = fechaFin; }

    @Nonnull
    public BigDecimal getTotalMerma() { return totalMerma; }
    public void setTotalMerma(@Nonnull BigDecimal totalMerma) { this.totalMerma = totalMerma; }

    @Nonnull
    public BigDecimal getPorcentajeMerma() { return porcentajeMerma; }
    public void setPorcentajeMerma(@Nonnull BigDecimal porcentajeMerma) { this.porcentajeMerma = porcentajeMerma; }

    @Nonnull
    public BigDecimal getMovimientoTotal() { return movimientoTotal; }
    public void setMovimientoTotal(@Nonnull BigDecimal movimientoTotal) { this.movimientoTotal = movimientoTotal; }

    @Nonnull
    public Map<String, BigDecimal> getMermaPorCausa() { return mermaPorCausa; }
    public void setMermaPorCausa(@Nonnull Map<String, BigDecimal> mermaPorCausa) { this.mermaPorCausa = mermaPorCausa; }

    @Nonnull
    public Map<String, BigDecimal> getMermaPorDepartamento() { return mermaPorDepartamento; }
    public void setMermaPorDepartamento(@Nonnull Map<String, BigDecimal> mermaPorDepartamento) { this.mermaPorDepartamento = mermaPorDepartamento; }

    @Nonnull
    public List<MovimientoDTO> getMovimientos() { return movimientos; }
    public void setMovimientos(@Nonnull List<MovimientoDTO> movimientos) { this.movimientos = movimientos; }

    /**
     * One shrinkage movement row — flattened view of Models.Inventario as
     * returned by {@code ShrinkageAnalysisService.getShrinkageMovements}.
     */
    public static class MovimientoDTO {

        private Integer codigo; // Inventario.codigo
        @Nullable private Long articuloCodigo; // Inventario.articulo.codigo
        @Nullable private String articuloNombre; // Inventario.articulo.nombre
        @Nullable private BigDecimal cantidad; // Inventario.cantidad
        @Nullable private String tipoMovimiento; // Inventario.tipoMovimiento (causa)
        @Nullable private Date fechaMovimiento; // Inventario.fechaMovimiento
        @Nullable private String notas; // Inventario.notas
        @Nullable private Long usuarioId; // Inventario.usuario.id
        @Nullable private String usuarioUsername; // Inventario.usuario.username

        public MovimientoDTO() {}

        public MovimientoDTO(Integer codigo, @Nullable Long articuloCodigo,
                             @Nullable String articuloNombre, @Nullable BigDecimal cantidad,
                             @Nullable String tipoMovimiento, @Nullable Date fechaMovimiento,
                             @Nullable String notas, @Nullable Long usuarioId,
                             @Nullable String usuarioUsername) {
            this.codigo = codigo;
            this.articuloCodigo = articuloCodigo;
            this.articuloNombre = articuloNombre;
            this.cantidad = cantidad;
            this.tipoMovimiento = tipoMovimiento;
            this.fechaMovimiento = fechaMovimiento;
            this.notas = notas;
            this.usuarioId = usuarioId;
            this.usuarioUsername = usuarioUsername;
        }

        public Integer getCodigo() { return codigo; }
        public void setCodigo(Integer codigo) { this.codigo = codigo; }

        @Nullable
        public Long getArticuloCodigo() { return articuloCodigo; }
        public void setArticuloCodigo(@Nullable Long articuloCodigo) { this.articuloCodigo = articuloCodigo; }

        @Nullable
        public String getArticuloNombre() { return articuloNombre; }
        public void setArticuloNombre(@Nullable String articuloNombre) { this.articuloNombre = articuloNombre; }

        @Nullable
        public BigDecimal getCantidad() { return cantidad; }
        public void setCantidad(@Nullable BigDecimal cantidad) { this.cantidad = cantidad; }

        @Nullable
        public String getTipoMovimiento() { return tipoMovimiento; }
        public void setTipoMovimiento(@Nullable String tipoMovimiento) { this.tipoMovimiento = tipoMovimiento; }

        @Nullable
        public Date getFechaMovimiento() { return fechaMovimiento; }
        public void setFechaMovimiento(@Nullable Date fechaMovimiento) { this.fechaMovimiento = fechaMovimiento; }

        @Nullable
        public String getNotas() { return notas; }
        public void setNotas(@Nullable String notas) { this.notas = notas; }

        @Nullable
        public Long getUsuarioId() { return usuarioId; }
        public void setUsuarioId(@Nullable Long usuarioId) { this.usuarioId = usuarioId; }

        @Nullable
        public String getUsuarioUsername() { return usuarioUsername; }
        public void setUsuarioUsername(@Nullable String usuarioUsername) { this.usuarioUsername = usuarioUsername; }
    }
}
