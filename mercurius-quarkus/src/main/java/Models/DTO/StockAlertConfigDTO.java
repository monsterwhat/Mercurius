package Models.DTO;

import jakarta.annotation.Nullable;

/**
 * Threshold configuration view for the stock-alert engine
 * ({@code Services.StockAlertService}), exposed by
 * {@code Controllers.Api.App.StockAlertConfigResource}.
 *
 * <p>The service manages two distinct kinds of threshold state; this DTO mirrors
 * both, field by field:</p>
 *
 * <ol>
 * <li><b>Engine constants (read-only facts).</b> {@code StockAlertService}
 * hardcodes these inside {@code calculateOptimalStock()} /
 * {@code calculateReorderQuantity()} and there is NO global settings row for
 * them (see the note in {@link AppSettingsDTO}: thresholds are per-article
 * values computed by the service). They are surfaced so operators can see the
 * effective behavior; PUT never accepts them.</li>
 *
 * <li><b>Per-article thresholds (writable via {@code PUT ?articulo=}).
 * Exactly the fields the service reads/writes on the article row:</b>
 * <ul>
 *   <li>{@code diasStockSeguridad} &mdash; {@code Articulos.diasStockSeguridad};
 *       {@code null} means "use the engine default"
 *       ({@code diasStockSeguridadPorDefecto}).</li>
 *   <li>{@code estadoAlertas} &mdash; {@code Articulos.estadoAlertas}; the gate
 *       {@code checkAndCreateStockAlerts()} consults before generating alerts
 *       for an article.</li>
 *   <li>{@code stockOptimoActual} &mdash; {@code Articulos.stockOptimo}; written
 *       back BY THE SERVICE during {@code checkAndCreateStockAlerts()}
 *       (read-only here, never accepted by PUT).</li>
 * </ul></li>
 * </ol>
 *
 * <p>Style: plain getters/setters with jakarta nullability annotations, same
 * contract as {@link StockAlertDTO} / {@link EmailTemplateDTO}.</p>
 */
public class StockAlertConfigDTO {

    // ── 1. Engine constants (read-only facts from StockAlertService) ────────

    /** Sales-velocity window: last 30 days of inventory movements. */
    private int ventanaVelocidadDias;

    /** Assumed supplier lead time: 3 days. */
    private int plazoEntregaDias;

    /** Safety-stock fallback when {@code Articulos.diasStockSeguridad} is null: 7 days. */
    private int diasStockSeguridadPorDefecto;

    /** Optimal-stock fallback when the article has no movements in the window:
     *  {@code diasStockSeguridad * 2}, or 14 days when that is null too. */
    private int stockOptimoRespaldoDias;

    /** Reorder buffer: 30 days of sales added on top of the optimal-stock gap. */
    private int bufferReordenDias;

    // ── 2. Per-article thresholds (the writable surface) ─────────────────────

    /** {@code Articulos.codigo} (PK); null when this DTO carries only the
     *  global engine view. */
    @Nullable
    private Long articuloCodigo;

    /** Flattened {@code Articulos.nombre} for display; null in the global view. */
    @Nullable
    private String articuloNombre;

    /** Raw stored safety-stock days; null = engine default applies. */
    @Nullable
    private Integer diasStockSeguridad;

    /** Whether the engine may generate alerts for this article. */
    private boolean estadoAlertas;

    /** Last optimal stock the service calculated for the article; null = never
     *  calculated. Read-only: recomputed by {@code checkAndCreateStockAlerts()}. */
    @Nullable
    private Integer stockOptimoActual;

    public StockAlertConfigDTO() {
    }

    public StockAlertConfigDTO(int ventanaVelocidadDias, int plazoEntregaDias,
                               int diasStockSeguridadPorDefecto, int stockOptimoRespaldoDias,
                               int bufferReordenDias, @Nullable Long articuloCodigo,
                               @Nullable String articuloNombre, @Nullable Integer diasStockSeguridad,
                               boolean estadoAlertas, @Nullable Integer stockOptimoActual) {
        this.ventanaVelocidadDias = ventanaVelocidadDias;
        this.plazoEntregaDias = plazoEntregaDias;
        this.diasStockSeguridadPorDefecto = diasStockSeguridadPorDefecto;
        this.stockOptimoRespaldoDias = stockOptimoRespaldoDias;
        this.bufferReordenDias = bufferReordenDias;
        this.articuloCodigo = articuloCodigo;
        this.articuloNombre = articuloNombre;
        this.diasStockSeguridad = diasStockSeguridad;
        this.estadoAlertas = estadoAlertas;
        this.stockOptimoActual = stockOptimoActual;
    }

    public int getVentanaVelocidadDias() {
        return ventanaVelocidadDias;
    }

    public void setVentanaVelocidadDias(int ventanaVelocidadDias) {
        this.ventanaVelocidadDias = ventanaVelocidadDias;
    }

    public int getPlazoEntregaDias() {
        return plazoEntregaDias;
    }

    public void setPlazoEntregaDias(int plazoEntregaDias) {
        this.plazoEntregaDias = plazoEntregaDias;
    }

    public int getDiasStockSeguridadPorDefecto() {
        return diasStockSeguridadPorDefecto;
    }

    public void setDiasStockSeguridadPorDefecto(int diasStockSeguridadPorDefecto) {
        this.diasStockSeguridadPorDefecto = diasStockSeguridadPorDefecto;
    }

    public int getStockOptimoRespaldoDias() {
        return stockOptimoRespaldoDias;
    }

    public void setStockOptimoRespaldoDias(int stockOptimoRespaldoDias) {
        this.stockOptimoRespaldoDias = stockOptimoRespaldoDias;
    }

    public int getBufferReordenDias() {
        return bufferReordenDias;
    }

    public void setBufferReordenDias(int bufferReordenDias) {
        this.bufferReordenDias = bufferReordenDias;
    }

    @Nullable
    public Long getArticuloCodigo() {
        return articuloCodigo;
    }

    public void setArticuloCodigo(@Nullable Long articuloCodigo) {
        this.articuloCodigo = articuloCodigo;
    }

    @Nullable
    public String getArticuloNombre() {
        return articuloNombre;
    }

    public void setArticuloNombre(@Nullable String articuloNombre) {
        this.articuloNombre = articuloNombre;
    }

    @Nullable
    public Integer getDiasStockSeguridad() {
        return diasStockSeguridad;
    }

    public void setDiasStockSeguridad(@Nullable Integer diasStockSeguridad) {
        this.diasStockSeguridad = diasStockSeguridad;
    }

    public boolean isEstadoAlertas() {
        return estadoAlertas;
    }

    public void setEstadoAlertas(boolean estadoAlertas) {
        this.estadoAlertas = estadoAlertas;
    }

    @Nullable
    public Integer getStockOptimoActual() {
        return stockOptimoActual;
    }

    public void setStockOptimoActual(@Nullable Integer stockOptimoActual) {
        this.stockOptimoActual = stockOptimoActual;
    }
}
