package Models.DTO;

import jakarta.annotation.Nonnull;
import java.math.BigDecimal;

/**
 * One row of the D-104 IVA declaration summary: aggregated totals for a single
 * fiscal period (month/year), matching the computation performed by
 * {@code Controllers.DeclaracionIVAController.calcular()} over the facturas
 * emitidas of the period.
 */
public class DeclaracionIVARowDTO {

    /** Periodo fiscal de la fila, e.g. "Enero 2026". */
    @Nonnull
    private String periodo;

    /** Suma de resumen.totalVentaNeta de las facturas emitidas del periodo. */
    @Nonnull
    private BigDecimal totalVentas;

    /** Suma de resumen.totalImpuesto de las facturas emitidas del periodo (IVA débito). */
    @Nonnull
    private BigDecimal totalImpuesto;

    public DeclaracionIVARowDTO() {
        this.periodo = "";
        this.totalVentas = BigDecimal.ZERO;
        this.totalImpuesto = BigDecimal.ZERO;
    }

    public DeclaracionIVARowDTO(@Nonnull String periodo, @Nonnull BigDecimal totalVentas,
                                @Nonnull BigDecimal totalImpuesto) {
        this.periodo = periodo;
        this.totalVentas = totalVentas;
        this.totalImpuesto = totalImpuesto;
    }

    @Nonnull
    public String getPeriodo() {
        return periodo;
    }

    public void setPeriodo(@Nonnull String periodo) {
        this.periodo = periodo;
    }

    @Nonnull
    public BigDecimal getTotalVentas() {
        return totalVentas;
    }

    public void setTotalVentas(@Nonnull BigDecimal totalVentas) {
        this.totalVentas = totalVentas;
    }

    @Nonnull
    public BigDecimal getTotalImpuesto() {
        return totalImpuesto;
    }

    public void setTotalImpuesto(@Nonnull BigDecimal totalImpuesto) {
        this.totalImpuesto = totalImpuesto;
    }
}
