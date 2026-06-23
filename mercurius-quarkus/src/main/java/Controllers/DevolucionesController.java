package Controllers;

import Controllers.SessionController;
import Models.Clients;
import Models.ComprobantesEmitidos;
import Models.Detalles.LineaDetalle;
import Models.Inventario;
import Models.NotaCredito;
import Services.AlertasService;
import Services.ClientService;
import Services.ComprobantesEmitidosService;
import Services.InventarioService;
import Services.NotaCreditoService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import lombok.Data;

@Data
@Named("devolucionesController")
@ViewScoped
public class DevolucionesController implements Serializable {

    @Inject
    private ComprobantesEmitidosService comprobantesService;

    @Inject
    private NotaCreditoService notaCreditoService;

    @Inject
    private InventarioService inventarioService;

    @Inject
    private ClientService clientService;

    @Inject
    private SessionController sessionController;

    @Inject
    private AlertasService alertasService;

    private String criterioBusqueda;
    private String tipoBusqueda; // "consecutivo" or "cliente"
    private ComprobantesEmitidos facturaSeleccionada;
    private List<ComprobantesEmitidos> facturasEncontradas;
    private List<LineaDevolucion> lineasDevolucion;
    private String motivo;
    private BigDecimal totalDevolucion;
    private List<NotaCredito> historialNotas;

    @PostConstruct
    public void init() {
        lineasDevolucion = new ArrayList<>();
        facturasEncontradas = new ArrayList<>();
        historialNotas = notaCreditoService.listAll();
        totalDevolucion = BigDecimal.ZERO;
        tipoBusqueda = "consecutivo";
    }

    public void buscarFactura() {
        if (criterioBusqueda == null || criterioBusqueda.trim().isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Busqueda", "Ingrese un criterio de busqueda"));
            return;
        }

        if ("consecutivo".equals(tipoBusqueda)) {
            List<ComprobantesEmitidos> todas = comprobantesService.listAll();
            facturasEncontradas = new ArrayList<>();
            if (todas != null) {
                for (ComprobantesEmitidos f : todas) {
                    if (f.getEncabezado() != null
                        && f.getEncabezado().getNumeroConsecutivo() != null
                        && f.getEncabezado().getNumeroConsecutivo().contains(criterioBusqueda)) {
                        facturasEncontradas.add(f);
                    }
                }
            }
        } else {
            List<Clients> clients = clientService.searchByName(criterioBusqueda);
            if (clients != null && !clients.isEmpty()) {
                Clients client = clients.get(0);
                facturasEncontradas = comprobantesService.listAll();
                if (facturasEncontradas != null) {
                    facturasEncontradas.removeIf(f ->
                        f.getEncabezado() == null
                        || f.getEncabezado().getReceptor() == null
                        || !criterioBusqueda.toLowerCase().contains(
                            f.getEncabezado().getReceptor().getNombre().toLowerCase()));
                }
            }
        }

        if (facturasEncontradas == null || facturasEncontradas.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Sin resultados", "No se encontraron facturas"));
        }
    }

    public void seleccionarFactura(ComprobantesEmitidos factura) {
        this.facturaSeleccionada = factura;
        lineasDevolucion = new ArrayList<>();
        totalDevolucion = BigDecimal.ZERO;

        if (factura.getDetalles() != null && factura.getDetalles().getLineasDetalle() != null) {
            for (LineaDetalle linea : factura.getDetalles().getLineasDetalle()) {
                LineaDevolucion ld = new LineaDevolucion();
                ld.setLineaDetalle(linea);
                ld.setCantidadOriginal(linea.getCantidad());
                ld.setCantidadDevolver(BigDecimal.ZERO);
                ld.setSeleccionado(false);
                lineasDevolucion.add(ld);
            }
        }

        facturasEncontradas = new ArrayList<>();
    }

    public void recalcularTotal() {
        totalDevolucion = BigDecimal.ZERO;
        if (lineasDevolucion != null) {
            for (LineaDevolucion ld : lineasDevolucion) {
                if (ld.isSeleccionado() && ld.getCantidadDevolver() != null) {
                    BigDecimal importe = ld.getLineaDetalle().getPrecioUnitario()
                        .multiply(ld.getCantidadDevolver());
                    totalDevolucion = totalDevolucion.add(importe);
                }
            }
        }
    }

    public void procesarDevolucion() {
        if (facturaSeleccionada == null) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Seleccione una factura primero"));
            return;
        }

        if (motivo == null || motivo.trim().isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error", "Ingrese el motivo de la devolucion"));
            return;
        }

        boolean haySeleccion = false;
        for (LineaDevolucion ld : lineasDevolucion) {
            if (ld.isSeleccionado() && ld.getCantidadDevolver() != null
                && ld.getCantidadDevolver().compareTo(BigDecimal.ZERO) > 0) {
                haySeleccion = true;
                break;
            }
        }

        if (!haySeleccion) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error",
                    "Seleccione al menos un articulo y especifique cantidad a devolver"));
            return;
        }

        try {
            NotaCredito nota = new NotaCredito();
            nota.setComprobanteOriginal(facturaSeleccionada);
            nota.setFecha(new Date());
            nota.setMotivo(motivo);
            nota.setMontoTotal(totalDevolucion);
            nota.setCliente(facturaSeleccionada.getEncabezado().getReceptor() != null
                ? null : null);
            nota.setUsuario(sessionController.getCurrentUser().getUsername());
            nota.setStatus(true);
            nota.setHaciendaEstado("PENDIENTE");
            notaCreditoService.create(nota);

            for (LineaDevolucion ld : lineasDevolucion) {
                if (ld.isSeleccionado() && ld.getCantidadDevolver() != null
                    && ld.getCantidadDevolver().compareTo(BigDecimal.ZERO) > 0) {

                    Inventario inv = new Inventario();
                    inv.setArticulo(null);
                    inv.setCantidad(ld.getCantidadDevolver().negate());
                    inv.setTipoMovimiento("Devolucion");
                    inv.setUsuario(sessionController.getCurrentUser());
                    inv.setFechaMovimiento(new Date());
                    inv.setNotas("Devolucion factura: "
                        + facturaSeleccionada.getEncabezado().getNumeroConsecutivo()
                        + " - " + motivo);
                    inv.setStatus(true);
                    inv.setProcessed(true);
                    inventarioService.create(inv);
                }
            }

            alertasService.registrarAlerta("Devolucion procesada",
                "Nota de credito creada por " + totalDevolucion + " - " + motivo,
                sessionController.getCurrentUser(), 0, "DevolucionesController.procesarDevolucion()",
                null, null);

            facturaSeleccionada = null;
            lineasDevolucion = new ArrayList<>();
            motivo = null;
            totalDevolucion = BigDecimal.ZERO;
            historialNotas = notaCreditoService.listAll();

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Exito",
                    "Devolucion procesada correctamente"));

        } catch (Exception e) {
            alertasService.registrarAlerta("Error devolucion",
                "Error al procesar devolucion: " + e.getMessage(),
                sessionController.getCurrentUser(), 0, "DevolucionesController.procesarDevolucion()",
                null, e.getMessage());

            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Error",
                    "Error al procesar devolucion: " + e.getMessage()));
        }
    }

    @Data
    public static class LineaDevolucion implements Serializable {
        private LineaDetalle lineaDetalle;
        private BigDecimal cantidadOriginal;
        private BigDecimal cantidadDevolver;
        private boolean seleccionado;
    }
}
