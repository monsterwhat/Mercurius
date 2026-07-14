package Controllers.Comprobantes;

import Models.Detalles.LineaDetalle;
import Services.ComprobanteService;
import Services.ComprobantesEmitidosService;
import Models.ComprobantesEmitidos;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import Utils.DiffUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import org.primefaces.PrimeFaces;
import org.primefaces.model.FilterMeta;
import org.primefaces.model.file.UploadedFile;
import org.primefaces.util.LangUtils;

import Controllers.SessionController; 
import Services.AlertasService; 

@Named("ComprobantesEmitidosController")
@Getter @Setter @ToString @EqualsAndHashCode
@ViewScoped
public class ComprobantesEmitidosController implements Serializable {
    
    @Inject @Nonnull ComprobantesEmitidosService comprobanteEmitidoService;
    @Inject @Nonnull ComprobanteService comprobanteService;
    @Inject @Nonnull SessionController sessionController;
    @Inject @Nonnull AlertasService alertasService;
    
    @Nullable
    private List<UploadedFile> files;
    @Nullable
    private List<ComprobantesEmitidos> comprobantesEmitidos;
    
    @Nullable
    private LineaDetalle lineaDetalle;
    
    @Nullable
    private ComprobantesEmitidos selectedComprobanteEmitido;
    @Nonnull
    private List<ComprobantesEmitidos> selectedComprobantes = new ArrayList<>();
    @Nullable
    private String comprobanteEmitidoFilter;
    @Nonnull
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    
    @PostConstruct
    public void init(){
        files = new ArrayList<>();
        filterBy = new ArrayList<>();
        selectedComprobanteEmitido = new ComprobantesEmitidos();
    }
    
    @Nonnull
    public List<ComprobantesEmitidos> comprobantesEmitidosList() {
        if (comprobantesEmitidos == null) {
            comprobantesEmitidos = comprobanteEmitidoService.listAll();
        }
        return comprobantesEmitidos;
    }
    
    public long comprobanteEmitidoCount() {
        return comprobanteEmitidoService.count();
    }

    public void deleteFactura() {
        if (selectedComprobanteEmitido != null) {
            try {
                String antes = DiffUtils.snapshotEntity(selectedComprobanteEmitido);
                comprobanteEmitidoService.softDelete(selectedComprobanteEmitido);
                alertasService.registrarAlerta("Factura eliminada", "La factura ha sido eliminada correctamente.", sessionController.getCurrentUser(), 0, "ComprobantesEmitidosController.deleteFactura", antes, DiffUtils.snapshotEntity(selectedComprobanteEmitido));
                clearFactura();
            } catch (RuntimeException e) {
                alertasService.registrarAlerta("Error", "Error al eliminar la factura.", sessionController.getCurrentUser(), 0, "ComprobantesEmitidosController.deleteFactura", selectedComprobanteEmitido.toString(), e.getMessage());
            }
        }
    }
    
    public void toggleFactura(){
        if(selectedComprobanteEmitido != null){
            try {
                String antes = DiffUtils.snapshotEntity(selectedComprobanteEmitido);
                comprobanteEmitidoService.toggle(selectedComprobanteEmitido);
                alertasService.registrarAlerta("Estado de factura cambiado", "El estado de la factura ha sido cambiado correctamente.", sessionController.getCurrentUser(), 0, "ComprobantesEmitidosController.toggleFactura", antes, DiffUtils.snapshotEntity(selectedComprobanteEmitido));
            } catch (RuntimeException e) {
                alertasService.registrarAlerta("Error", "Error al cambiar el estado de la factura.", sessionController.getCurrentUser(), 0, "ComprobantesEmitidosController.toggleFactura", selectedComprobanteEmitido.toString(), e.getMessage());
            }
        }
    }

    public void clearFactura() {
        selectedComprobanteEmitido = null;
    }
    
    public void clearCache(){
        comprobantesEmitidos = null;
    }

    @Nonnull
    public List<ComprobantesEmitidos> getFilteredComprobantesEmitidos() {
        if(comprobantesEmitidos == null){
            comprobantesEmitidos = comprobanteEmitidoService.listAll();
        }
        if (comprobanteEmitidoFilter != null && !comprobanteEmitidoFilter.isEmpty()) {
            return comprobantesEmitidosList().stream()
                    .filter(comprobanteEmitido -> globalFilterFunction(comprobanteEmitido, comprobanteEmitidoFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        } else {
            return comprobantesEmitidosList();
        }
    }
    
    public void reenviarSeleccionados() {
        if (selectedComprobantes == null || selectedComprobantes.isEmpty()) {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_WARN, "Selección vacía",
                    "Seleccione al menos una factura pendiente para reenviar"));
            return;
        }
        int enviadas = 0;
        int fallidas = 0;
        for (ComprobantesEmitidos factura : selectedComprobantes) {
            if (factura.getHaciendaEstado() == null || "PENDIENTE".equalsIgnoreCase(factura.getHaciendaEstado())) {
                boolean ok = comprobanteService.enviarComprobanteAHacienda(factura);
                if (ok) enviadas++;
                else fallidas++;
            }
        }
        alertasService.registrarAlerta("Reenvío masivo",
            "Enviadas: " + enviadas + ", Fallidas: " + fallidas,
            sessionController.getCurrentUser(), 0,
            "ComprobantesEmitidosController.reenviarSeleccionados()", null, null);
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO, "Reenvío completado",
                "Enviadas: " + enviadas + ", Fallidas: " + fallidas));
        selectedComprobantes.clear();
        clearCache();
    }

    public void selectAllPendientes() {
        List<ComprobantesEmitidos> all = comprobantesEmitidosList();
        if (all == null) return;
        selectedComprobantes = all.stream()
            .filter(f -> f.getHaciendaEstado() == null || "PENDIENTE".equalsIgnoreCase(f.getHaciendaEstado()))
            .collect(Collectors.toList());
        PrimeFaces.current().ajax().update("recibos");
    }

    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        ComprobantesEmitidos comprobanteEmitido = (ComprobantesEmitidos) value;
        return comprobanteEmitido.getEncabezado().getCodigoActividadEmisor().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getCondicionVenta().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getEmisor().getNombre().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getEmisor().getCorreosElectronicos().contains(filterText.toLowerCase())
                || comprobanteEmitido.getEncabezado().getEmisor().getIdentificacion().getNumero().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getEmisor().getNombreComercial().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getFechaEmision().toString().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getNumeroConsecutivo().toLowerCase().contains(filterText);
    }
    
}
