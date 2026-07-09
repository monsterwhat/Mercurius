package Controllers.Tributacion;

import Controllers.SessionController;
import Models.ComprobantesEmitidos;
import Services.ComprobantesEmitidosService;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

@Data
@Named(value = "tributacionController")
@ViewScoped
public class TributacionController implements Serializable {

    @Inject
    @Nonnull
    private ComprobantesEmitidosService comprobantesService;

    @Inject
    @Nonnull
    private SessionController currentSession;

    @Nonnull
    private Date fechaInicio;
    @Nonnull
    private Date fechaFin;
    @Nullable
    private List<ComprobantesEmitidos> facturas;
    @Nullable
    private Map<String, BigDecimal> impuestosAgrupados;
    @Nonnull
    private BigDecimal totalBaseImponible;
    @Nonnull
    private BigDecimal totalImpuesto;
    @Nonnull
    private BigDecimal totalComprobantes;

    public TributacionController() {
    }

    @PostConstruct
    public void init() {
        fechaInicio = new Date();
        fechaFin = new Date();
        cargarDatos();
    }

    public void cargarDatos() {
        try {
            List<ComprobantesEmitidos> result = comprobantesService.listAll();
            facturas = result != null ? result : null;
            calcularTotales();
        } catch (RuntimeException e) {
            facturas = null;
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", 
                    "No se pudieron cargar los datos tributarios: " + e.getMessage()));
        }
    }

    public void filtrarFecha() {
        if (fechaInicio != null && fechaFin != null) {
            final Date fechaFinCalculated = new Date(fechaFin.getTime() + 86400000);
            
            facturas = comprobantesService.listAll().stream()
                .filter(f -> {
                    if (f.getEncabezado() == null || f.getEncabezado().getFechaEmision() == null) {
                        return false;
                    }
                    Object fechaObj = f.getEncabezado().getFechaEmision();
                    Date fecha;
                    if (fechaObj instanceof LocalDateTime) {
                        fecha = Date.from(((LocalDateTime) fechaObj).atZone(ZoneId.systemDefault()).toInstant());
                    } else if (fechaObj instanceof Date) {
                        fecha = (Date) fechaObj;
                    } else {
                        return false;
                    }
                    return !fecha.before(fechaInicio) && fecha.before(fechaFinCalculated);
                })
                .toList();
            calcularTotales();
        }
    }

    private void calcularTotales() {
        if (facturas == null) {
            totalBaseImponible = BigDecimal.ZERO;
            totalImpuesto = BigDecimal.ZERO;
            totalComprobantes = BigDecimal.ZERO;
            return;
        }
        
        impuestosAgrupados = new HashMap<>();
        totalBaseImponible = BigDecimal.ZERO;
        totalImpuesto = BigDecimal.ZERO;
        totalComprobantes = BigDecimal.ZERO;

        for (ComprobantesEmitidos factura : facturas) {
            if (factura.getResumen() != null) {
                if (factura.getResumen().getTotalComprobante() != null) {
                    totalComprobantes = totalComprobantes.add(factura.getResumen().getTotalComprobante());
                }
                if (factura.getResumen().getTotalImpuesto() != null) {
                    totalImpuesto = totalImpuesto.add(factura.getResumen().getTotalImpuesto());
                }
            }
        }
    }

    public void generarReporte() {
        filtrarFecha();
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Reporte Generado", 
                "Se han calculado los impuestos de " + (facturas != null ? facturas.size() : 0) + " facturas"));
    }

    public void limpiarFiltros() {
        fechaInicio = new Date();
        fechaFin = new Date();
        cargarDatos();
    }

    public int getTotalFacturas() {
        return facturas != null ? facturas.size() : 0;
    }

    public int getFacturasAceptadas() {
        if (facturas == null) return 0;
        return (int) facturas.stream()
            .filter(f -> f.getStatus() != null && f.getStatus())
            .count();
    }

    public int getFacturasPendientes() {
        if (facturas == null) return 0;
        return (int) facturas.stream()
            .filter(f -> f.getStatus() == null || !f.getStatus())
            .count();
    }
}
