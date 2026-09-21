package Models.DTO;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * Data Transfer Object for {@link Models.DepartamentoMetrico}.
 * Almacena las métricas calculadas de rendimiento para cada Departamento (Proveedor).
 * Mirrors the entity's field types exactly; the departamento relation is flattened
 * to its id plus display string (nombre).
 */
public class DepartamentoMetricoDTO {

    private Long id;

    @Nullable
    private Long departamentoId;

    @Nullable
    private String departamentoNombre;

    @Nonnull
    private Date fechaCalculo;

    /** Total de facturas recibidas de este proveedor */
    private int totalFacturasRecibidas;

    /** Facturas que ya fueron pagadas */
    private int facturasPagadas;

    /** Monto total de compras (suma de totalComprobante) */
    @Nonnull
    private BigDecimal montoTotalCompras;

    /** Monto promedio por factura */
    @Nonnull
    private BigDecimal montoPromedioFactura;

    /** Tiempo promedio de entrega en días (calculado desde fechas de factura) */
    private double tiempoEntregaPromedio;

    /** Porcentaje de entregas a tiempo (0-100) */
    private double tasaOnTimeDelivery;

    /** Cantidad de artículos distintos comprados a este proveedor */
    private int articulosComprados;

    /** Score de rendimiento calculado (0-100) */
    private double score;

    public DepartamentoMetricoDTO() {
    }

    public DepartamentoMetricoDTO(@Nonnull Long id, @Nullable Long departamentoId,
                                  @Nullable String departamentoNombre, @Nonnull Date fechaCalculo,
                                  int totalFacturasRecibidas, int facturasPagadas,
                                  @Nonnull BigDecimal montoTotalCompras,
                                  @Nonnull BigDecimal montoPromedioFactura,
                                  double tiempoEntregaPromedio, double tasaOnTimeDelivery,
                                  int articulosComprados, double score) {
        this.id = id;
        this.departamentoId = departamentoId;
        this.departamentoNombre = departamentoNombre;
        this.fechaCalculo = fechaCalculo;
        this.totalFacturasRecibidas = totalFacturasRecibidas;
        this.facturasPagadas = facturasPagadas;
        this.montoTotalCompras = montoTotalCompras;
        this.montoPromedioFactura = montoPromedioFactura;
        this.tiempoEntregaPromedio = tiempoEntregaPromedio;
        this.tasaOnTimeDelivery = tasaOnTimeDelivery;
        this.articulosComprados = articulosComprados;
        this.score = score;
    }

    @Nonnull
    public Long getId() {
        return id;
    }

    public void setId(@Nonnull Long id) {
        this.id = id;
    }

    @Nullable
    public Long getDepartamentoId() {
        return departamentoId;
    }

    public void setDepartamentoId(@Nullable Long departamentoId) {
        this.departamentoId = departamentoId;
    }

    @Nullable
    public String getDepartamentoNombre() {
        return departamentoNombre;
    }

    public void setDepartamentoNombre(@Nullable String departamentoNombre) {
        this.departamentoNombre = departamentoNombre;
    }

    @Nonnull
    public Date getFechaCalculo() {
        return fechaCalculo;
    }

    public void setFechaCalculo(@Nonnull Date fechaCalculo) {
        this.fechaCalculo = fechaCalculo;
    }

    public int getTotalFacturasRecibidas() {
        return totalFacturasRecibidas;
    }

    public void setTotalFacturasRecibidas(int totalFacturasRecibidas) {
        this.totalFacturasRecibidas = totalFacturasRecibidas;
    }

    public int getFacturasPagadas() {
        return facturasPagadas;
    }

    public void setFacturasPagadas(int facturasPagadas) {
        this.facturasPagadas = facturasPagadas;
    }

    @Nonnull
    public BigDecimal getMontoTotalCompras() {
        return montoTotalCompras;
    }

    public void setMontoTotalCompras(@Nonnull BigDecimal montoTotalCompras) {
        this.montoTotalCompras = montoTotalCompras;
    }

    @Nonnull
    public BigDecimal getMontoPromedioFactura() {
        return montoPromedioFactura;
    }

    public void setMontoPromedioFactura(@Nonnull BigDecimal montoPromedioFactura) {
        this.montoPromedioFactura = montoPromedioFactura;
    }

    public double getTiempoEntregaPromedio() {
        return tiempoEntregaPromedio;
    }

    public void setTiempoEntregaPromedio(double tiempoEntregaPromedio) {
        this.tiempoEntregaPromedio = tiempoEntregaPromedio;
    }

    public double getTasaOnTimeDelivery() {
        return tasaOnTimeDelivery;
    }

    public void setTasaOnTimeDelivery(double tasaOnTimeDelivery) {
        this.tasaOnTimeDelivery = tasaOnTimeDelivery;
    }

    public int getArticulosComprados() {
        return articulosComprados;
    }

    public void setArticulosComprados(int articulosComprados) {
        this.articulosComprados = articulosComprados;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }
}
