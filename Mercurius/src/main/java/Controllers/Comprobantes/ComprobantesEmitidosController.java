package Controllers.Comprobantes;

import Services.ComprobantesEmitidosService;
import Models.Comprobantes.ComprobantesEmitidos;
import Models.Comprobantes.Detalles.*;
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

@Named("ComprobantesEmitidosController")
@Data
@ViewScoped
public class ComprobantesEmitidosController implements Serializable {
    
    @Inject ComprobantesEmitidosService comprobanteEmitidoService;
    
    private List<UploadedFile> files;
    private List<ComprobantesEmitidos> comprobantesEmitidos;
    
    private LineaDetalle lineaDetalle;
    
    private ComprobantesEmitidos selectedComprobanteEmitido;
    private String comprobanteEmitidoFilter;
    private List<FilterMeta> filterBy;
    private boolean globalFilterOnly;
    
    @PostConstruct
    public void init(){
        files = new ArrayList<>();
        filterBy = new ArrayList<>();
        selectedComprobanteEmitido = new ComprobantesEmitidos();
    }
    
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
            comprobanteEmitidoService.softDelete(selectedComprobanteEmitido);
            clearFactura();
        }
    }
    
    public void toggleFactura(){
        if(selectedComprobanteEmitido != null){
            comprobanteEmitidoService.toggle(selectedComprobanteEmitido);
        }
    }

    public void clearFactura() {
        selectedComprobanteEmitido = null;
    }
    
    public void clearCache(){
        comprobantesEmitidos = null;
    }

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
    
    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        ComprobantesEmitidos comprobanteEmitido = (ComprobantesEmitidos) value;
        return comprobanteEmitido.getEncabezado().getCodigoActividad().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getCondicionVenta().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getEmisor().getNombre().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getEmisor().getCorreoElectronico().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getEmisor().getIdentificacion().getNumero().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getEmisor().getNombreComercial().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getFechaEmision().toString().toLowerCase().contains(filterText)
                || comprobanteEmitido.getEncabezado().getNumeroConsecutivo().toLowerCase().contains(filterText);
    }
    
}
