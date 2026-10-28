package Models.DTO;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.List;

/**
 * One series of a {@link ReportChartPayload} (P2 Phase 2.1).
 *
 * <p>A chart payload always carries an array of these (1..n), even for the
 * single-series reports: the historical multi-line chart
 * ({@code ventas-fecha-historica}) has to fit the same contract without a
 * second schema.</p>
 *
 * <p>{@code values} is never {@code null}: {@code web/report-chart.js} expects a
 * JSON array, and a {@code null} there would surface as a Chart.js error inside
 * an otherwise empty frame. Values are BigDecimal so the serializer can emit
 * plain JSON numbers ({@code toPlainString} semantics, never {@code 1.2E+3}).</p>
 *
 * @param label  legend / tooltip label. {@code null} renders as an empty label.
 * @param values one value per {@link ReportChartPayload#labels()} entry; empty
 *               when the report has no rows.
 */
public record ReportChartSeries(@Nullable String label, @Nullable List<BigDecimal> values) {

    public ReportChartSeries {
        values = values == null ? List.of() : values;
    }
}
