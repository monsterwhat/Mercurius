package Controllers.Comprobantes;

import Models.Detalles.LineaDetalle;
import Services.ComprobantesEmitidosService;
import Models.ComprobantesEmitidos;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import lombok.Data;

import org.primefaces.model.FilterMeta;
import org.primefaces.model.file.UploadedFile;
import org.primefaces.util.LangUtils;

import Controllers.SessionController; 
import Services.AlertasService; 

@Named("ComprobantesEmitidosController")
@Data
@ViewScoped
public class ComprobantesEmitidosController implements Serializable {
    
    @Inject @Nonnull ComprobantesEmitidosService comprobanteEmitidoService;
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
                var oldComprobante = selectedComprobanteEmitido;
                comprobanteEmitidoService.softDelete(selectedComprobanteEmitido);
                alertasService.registrarAlerta("Factura eliminada", "La factura ha sido eliminada correctamente.", sessionController.getCurrentUser(), 0, "ComprobantesEmitidosController.deleteFactura", oldComprobante.toString(), selectedComprobanteEmitido.toString());
                clearFactura();
            } catch (RuntimeException e) {
                alertasService.registrarAlerta("Error", "Error al eliminar la factura.", sessionController.getCurrentUser(), 0, "ComprobantesEmitidosController.deleteFactura", selectedComprobanteEmitido.toString(), e.getMessage());
            }
        }
    }
    
    public void toggleFactura(){
        if(selectedComprobanteEmitido != null){
            try {
                var oldComprobante = selectedComprobanteEmitido;
                comprobanteEmitidoService.toggle(selectedComprobanteEmitido);
                alertasService.registrarAlerta("Estado de factura cambiado", "El estado de la factura ha sido cambiado correctamente.", sessionController.getCurrentUser(), 0, "ComprobantesEmitidosController.toggleFactura", oldComprobante.toString(), selectedComprobanteEmitido.toString());
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
