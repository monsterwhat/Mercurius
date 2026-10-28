package Models.DTO;

import jakarta.annotation.Nullable;
import java.util.List;

/**
 * The ONE machine-readable payload behind a report chart (P2 Phase 2.1).
 *
 * <p>Emitted by the resource as a JSON script tag through
 * {@link Utils.ReportChartJson#toScriptJson(Object)} and read by
 * {@code web/report-chart.js}; Qute templates never concatenate labels, values
 * or dates into JavaScript. That is what removes the two shipped defects this
 * contract replaces: a server-rendered JSON with a trailing comma
 * ({@code 3f7be8e}) and a {@code java.sql.Date} reaching {@code toInstant()}
 * ({@code 21834c5}, {@code 06f108e}).</p>
 *
 * <p>Wire shape (one object per canvas):</p>
 * <pre>{@code
 * {
 *   "id": "datos-grafica-departamentos",
 *   "type": "bar",
 *   "label": "Ventas",
 *   "labels": ["Enero", "Febrero"],
 *   "series": [{ "label": "Ventas", "values": [1200, 3400] }],
 *   "legend": false,
 *   "emptyLabel": "Sin datos para el período"
 * }
 * }</pre>
 *
 * <p>Labels are already display strings. A date label must be a {@code LocalDate}
 * formatted by the resource (ISO {@code yyyy-MM-dd} or the page's display format),
 * never a {@code java.sql.Date}/{@code java.util.Date} instance.</p>
 *
 * @param id         DOM id of the {@code <script type="application/json">} that
 *                   carries this payload; also the canvas'
 *                   {@code data-report-chart} marker value.
 * @param type       {@code bar} or {@code line}. Blank/null defaults to {@code bar}.
 * @param label      series-level label (axis/tooltip); may be null.
 * @param labels     category labels, one per value.
 * @param series     one or more series; never null.
 * @param legend     whether Chart.js draws a legend. One-series reports set
 *                   {@code false} because the card title already names the series.
 * @param emptyLabel optional in-frame empty-state copy.
 */
public record ReportChartPayload(
        @Nullable String id,
        @Nullable String type,
        @Nullable String label,
        @Nullable List<String> labels,
        @Nullable List<ReportChartSeries> series,
        boolean legend,
        @Nullable String emptyLabel) {

    public ReportChartPayload {
        labels = labels == null ? List.of() : labels;
        series = series == null ? List.of() : series;
        if (type == null || type.isBlank()) {
            type = "bar";
        }
    }
}
