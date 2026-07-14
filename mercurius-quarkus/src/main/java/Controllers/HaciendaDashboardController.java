package Controllers;

import Controllers.SessionController;
import Models.ComprobantesEmitidos;
import Models.NotaCredito;
import Services.AlertasService;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Services.ComprobantesRecibidosService;
import Services.NotaCreditoService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Getter @Setter @ToString @EqualsAndHashCode
@Named("haciendaDashboardController")
@ViewScoped
public class HaciendaDashboardController implements Serializable {

    @Inject @Nonnull private ComprobantesEmitidosService emitidosService;
    @Inject @Nonnull private ComprobantesRecibidosService recibidosService;
    @Inject @Nonnull private NotaCreditoService notaCreditoService;
    @Inject @Nonnull private ComprobanteService comprobanteService;
    @Inject @Nonnull private SessionController sessionController;
    @Inject @Nonnull private AlertasService alertasService;

    // --- KPIs ---
    private long totalEmitidos;
    private long totalAceptados;
    private long totalRechazados;
    private long totalPendientes;
    private long totalEnviadosSinRespuesta;
    private long totalNotasCredito;

    // --- Hoy ---
    private long emitidosHoy;
    private long aceptadosHoy;
    private long rechazadosHoy;

    // --- Montos ---
    @Nullable private BigDecimal montoTotalAceptados;
    @Nullable private BigDecimal montoTotalPendientes;

    // --- Listas ---
    @Nullable private List<DashboardRow> ultimosMovimientos;
    @Nullable private List<ComprobantesEmitidos> pendientesEnvio;
    @Nullable private List<ComprobantesEmitidos> rechazadosRecientes;

    // --- Estadísticas por día (últimos 7 días) ---
    @Nullable private List<DailyStat> dailyStats;

    @PostConstruct
    public void init() {
        cargarDashboard();
    }

    public void cargarDashboard() {
        try {
            List<ComprobantesEmitidos> todos = emitidosService.listAll();
            if (todos == null) todos = Collections.emptyList();

            // Fecha de hoy
            LocalDate hoy = LocalDate.now();
            LocalDateTime inicioHoy = hoy.atStartOfDay();
            LocalDateTime finHoy = hoy.plusDays(1).atStartOfDay();

            // Totales por estado
            totalEmitidos = todos.size();
            totalAceptados = todos.stream()
                .filter(c -> "ACEPTADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .count();
            totalRechazados = todos.stream()
                .filter(c -> "RECHAZADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .count();
            totalPendientes = todos.stream()
                .filter(c -> c.getHaciendaEstado() == null
                    || "PENDIENTE".equalsIgnoreCase(c.getHaciendaEstado())
                    || c.getHaciendaEstado().isEmpty())
                .count();
            totalEnviadosSinRespuesta = todos.stream()
                .filter(c -> "ENVIADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .count();

            // Hoy
            emitidosHoy = todos.stream()
                .filter(c -> c.getEncabezado() != null && c.getEncabezado().getFechaEmision() != null)
                .filter(c -> !c.getEncabezado().getFechaEmision().isBefore(inicioHoy)
                    && c.getEncabezado().getFechaEmision().isBefore(finHoy))
                .count();
            aceptadosHoy = todos.stream()
                .filter(c -> "ACEPTADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .filter(c -> c.getHaciendaFechaRespuesta() != null)
                .filter(c -> !c.getHaciendaFechaRespuesta().isBefore(inicioHoy)
                    && c.getHaciendaFechaRespuesta().isBefore(finHoy))
                .count();
            rechazadosHoy = todos.stream()
                .filter(c -> "RECHAZADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .filter(c -> c.getHaciendaFechaRespuesta() != null)
                .filter(c -> !c.getHaciendaFechaRespuesta().isBefore(inicioHoy)
                    && c.getHaciendaFechaRespuesta().isBefore(finHoy))
                .count();

            // Montos
            montoTotalAceptados = todos.stream()
                .filter(c -> "ACEPTADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .filter(c -> c.getResumen() != null && c.getResumen().getTotalComprobante() != null)
                .map(c -> c.getResumen().getTotalComprobante())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            montoTotalPendientes = todos.stream()
                .filter(c -> c.getHaciendaEstado() == null
                    || "PENDIENTE".equalsIgnoreCase(c.getHaciendaEstado())
                    || c.getHaciendaEstado().isEmpty())
                .filter(c -> c.getResumen() != null && c.getResumen().getTotalComprobante() != null)
                .map(c -> c.getResumen().getTotalComprobante())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            // Pendientes de envío
            pendientesEnvio = todos.stream()
                .filter(c -> c.getHaciendaEstado() == null
                    || "PENDIENTE".equalsIgnoreCase(c.getHaciendaEstado())
                    || c.getHaciendaEstado().isEmpty())
                .sorted(Comparator.comparing(
                    (ComprobantesEmitidos c) -> c.getEncabezado() != null ? c.getEncabezado().getFechaEmision() : LocalDateTime.MIN,
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(20)
                .collect(Collectors.toList());

            // Rechazados recientes
            rechazadosRecientes = todos.stream()
                .filter(c -> "RECHAZADO".equalsIgnoreCase(c.getHaciendaEstado()))
                .sorted(Comparator.comparing(
                    (ComprobantesEmitidos c) -> c.getHaciendaFechaRespuesta(),
                    Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(10)
                .collect(Collectors.toList());

            // Notas de crédito
            List<NotaCredito> notas = notaCreditoService.listAll();
            totalNotasCredito = notas != null ? notas.size() : 0;

            // Últimos movimientos (combinados)
            ultimosMovimientos = new ArrayList<>();
            for (ComprobantesEmitidos c : todos) {
                if (c.getEncabezado() != null && c.getEncabezado().getFechaEmision() != null) {
                    ultimosMovimientos.add(new DashboardRow(
                        c.getId(),
                        c.getEncabezado().getNumeroConsecutivo(),
                        "EMITIDO",
                        c.getHaciendaEstado(),
                        c.getEncabezado().getFechaEmision(),
                        c.getResumen() != null ? c.getResumen().getTotalComprobante() : BigDecimal.ZERO
                    ));
                }
            }
            if (ultimosMovimientos != null) {
                ultimosMovimientos.sort(Comparator.comparing(DashboardRow::getFecha, Comparator.nullsLast(Comparator.reverseOrder())));
                if (ultimosMovimientos.size() > 30) {
                    ultimosMovimientos = ultimosMovimientos.subList(0, 30);
                }
            }

            // Estadísticas últimos 7 días
            dailyStats = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate dia = hoy.minusDays(i);
                LocalDateTime inicio = dia.atStartOfDay();
                LocalDateTime fin = dia.plusDays(1).atStartOfDay();
                long diaEmitidos = todos.stream()
                    .filter(c -> c.getEncabezado() != null && c.getEncabezado().getFechaEmision() != null)
                    .filter(c -> !c.getEncabezado().getFechaEmision().isBefore(inicio)
                        && c.getEncabezado().getFechaEmision().isBefore(fin))
                    .count();
                long diaAceptados = todos.stream()
                    .filter(c -> c.getHaciendaFechaRespuesta() != null)
                    .filter(c -> !c.getHaciendaFechaRespuesta().isBefore(inicio)
                        && c.getHaciendaFechaRespuesta().isBefore(fin))
                    .filter(c -> "ACEPTADO".equalsIgnoreCase(c.getHaciendaEstado()))
                    .count();
                dailyStats.add(new DailyStat(dia.format(DateTimeFormatter.ofPattern("dd/MM")), diaEmitidos, (int) diaAceptados));
            }

        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error", "Error al cargar dashboard Hacienda: " + e.getMessage(),
                sessionController.getCurrentUser(), 0,
                "HaciendaDashboardController.cargarDashboard()", null, e.getMessage());
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "No se pudieron cargar los datos del dashboard"));
        }
    }

    public void refresh() {
        cargarDashboard();
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Dashboard actualizado", "Los datos se han recargado correctamente"));
    }

    // --- Getters for UI ---

    public long getTotalSinEstado() {
        return totalPendientes;
    }

    @Nullable
    public String getMontoTotalAceptadosFormateado() {
        if (montoTotalAceptados == null) return "₡0.00";
        return "₡" + String.format("%,.2f", montoTotalAceptados);
    }

    @Nullable
    public String getMontoTotalPendientesFormateado() {
        if (montoTotalPendientes == null) return "₡0.00";
        return "₡" + String.format("%,.2f", montoTotalPendientes);
    }

    public double getTasaExito() {
        if (totalEmitidos == 0) return 100.0;
        return Math.round(((double) totalAceptados / totalEmitidos) * 1000.0) / 10.0;
    }

    // --- Inner classes ---

    @Data
    public static class DashboardRow {
        private final long id;
        @Nullable private final String numeroConsecutivo;
        @Nonnull private final String tipo;
        @Nullable private final String estadoHacienda;
        @Nullable private final LocalDateTime fecha;
        @Nonnull private final BigDecimal total;
    }

    @Data
    public static class DailyStat {
        @Nonnull private final String dia;
        private final long emitidos;
        private final int aceptados;
    }
}
