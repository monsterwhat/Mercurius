package Controllers;

import Controllers.SessionController;
import Models.ComprobantesEmitidos;
import Models.ComprobantesRecibidos;
import Services.AlertasService;
import Services.ComprobantesEmitidosService;
import Services.ComprobantesRecibidosService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Getter @Setter @ToString @EqualsAndHashCode
@Named("declaracionIVAController")
@ViewScoped
public class DeclaracionIVAController implements Serializable {

    @Inject @Nonnull
    private ComprobantesEmitidosService emitidosService;

    @Inject @Nonnull
    private ComprobantesRecibidosService recibidosService;

    @Inject @Nonnull
    private SessionController sessionController;

    @Inject @Nonnull
    private AlertasService alertasService;

    private int mes;
    private int anio;

    @Nonnull
    private BigDecimal totalVentas;
    @Nonnull
    private BigDecimal totalCompras;
    @Nonnull
    private BigDecimal ivaDebito;
    @Nonnull
    private BigDecimal ivaCredito;
    @Nonnull
    private BigDecimal ivaNeto;
    private int totalFacturasEmitidas;
    private int totalFacturasRecibidas;

    @Nullable
    private List<ComprobantesEmitidos> facturasEmitidas;
    @Nullable
    private List<ComprobantesRecibidos> facturasRecibidas;

    @PostConstruct
    public void init() {
        LocalDate now = LocalDate.now();
        mes = now.getMonthValue();
        anio = now.getYear();
        calcular();
    }

    public void calcular() {
        try {
            LocalDate startDate = LocalDate.of(anio, mes, 1);
            LocalDate endDate = startDate.plusMonths(1).minusDays(1);

            Date start = Date.from(startDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date end = Date.from(endDate.atTime(23, 59, 59).atZone(ZoneId.systemDefault()).toInstant());

            facturasEmitidas = emitidosService.listByDateRange(start, end);
            facturasRecibidas = recibidosService.listByDateRange(start, end);

            totalVentas = BigDecimal.ZERO;
            ivaDebito = BigDecimal.ZERO;
            totalFacturasEmitidas = 0;

            if (facturasEmitidas != null) {
                totalFacturasEmitidas = facturasEmitidas.size();
                for (ComprobantesEmitidos f : facturasEmitidas) {
                    if (f.getResumen() != null) {
                        if (f.getResumen().getTotalVentaNeta() != null) {
                            totalVentas = totalVentas.add(f.getResumen().getTotalVentaNeta());
                        }
                        if (f.getResumen().getTotalImpuesto() != null) {
                            ivaDebito = ivaDebito.add(f.getResumen().getTotalImpuesto());
                        }
                    }
                }
            }

            totalCompras = BigDecimal.ZERO;
            ivaCredito = BigDecimal.ZERO;
            totalFacturasRecibidas = 0;

            if (facturasRecibidas != null) {
                totalFacturasRecibidas = facturasRecibidas.size();
                for (ComprobantesRecibidos f : facturasRecibidas) {
                    if (f.getResumen() != null) {
                        if (f.getResumen().getTotalVentaNeta() != null) {
                            totalCompras = totalCompras.add(f.getResumen().getTotalVentaNeta());
                        }
                        if (f.getResumen().getTotalImpuesto() != null) {
                            ivaCredito = ivaCredito.add(f.getResumen().getTotalImpuesto());
                        }
                    }
                }
            }

            ivaNeto = ivaDebito.subtract(ivaCredito);

        } catch (RuntimeException e) {
            alertasService.registrarAlerta("Error IVA",
                "Error al calcular declaracion IVA: " + e.getMessage(),
                sessionController.getCurrentUser(), 0, "DeclaracionIVAController.calcular()",
                null, e.getMessage());

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", e.getMessage()));
        }
    }

    public @Nonnull String[] getMeses() {
        return new String[]{
            "Enero", "Febrero", "Marzo", "Abril", "Mayo", "Junio",
            "Julio", "Agosto", "Setiembre", "Octubre", "Noviembre", "Diciembre"
        };
    }

    public @Nonnull String getNombreMes() {
        return getMeses()[mes - 1];
    }
}
