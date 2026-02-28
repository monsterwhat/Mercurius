package Controllers;

import Controllers.Settings.SettingsDirController;
import Models.Articulos.Articulos;
import Services.ArticulosService;
import Services.PrinterService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
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
import org.primefaces.util.LangUtils;

@Data
@Named(value = "EtiquetasController")
@ViewScoped
public class EtiquetasController implements Serializable {

    @Inject
    private ArticulosService articuloService;
    @Inject
    private PrinterService printer;
    @Inject
    private SettingsDirController directoryConfig;

    private List<Articulos> articulos;
    private List<Articulos> selectedArticulos;
    private String globalFilter;
    private Integer cantidadEtiquetas = 1;
    private Integer cantidadCopias = 1;

    @PostConstruct
    public void init() {
        selectedArticulos = new ArrayList<>();
    }

    public List<Articulos> getArticulos() {
        if (articulos == null) {
            articulos = articuloService.ListAllEnabled();
        }
        return articulos;
    }

    public List<Articulos> getFilteredArticulos() {
        List<Articulos> result = getArticulos();

        if (globalFilter != null && !globalFilter.trim().isEmpty()) {
            String filterText = globalFilter.trim().toLowerCase();
            result = result.stream()
                    .filter(articulo -> globalFilterFunction(articulo, filterText, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        }

        return result;
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Articulos articulo = (Articulos) value;
        return String.valueOf(articulo.getCodigo()).contains(filterText)
                || (articulo.getNombre() != null && articulo.getNombre().toLowerCase().contains(filterText))
                || (articulo.getCodigoBarra() != null && articulo.getCodigoBarra().toLowerCase().contains(filterText));
    }

    public void toggleSelection(Articulos articulo) {
        boolean found = false;
        for (Articulos a : selectedArticulos) {
            if (a.getCodigo() != null && a.getCodigo().equals(articulo.getCodigo())) {
                found = true;
                break;
            }
        }
        if (found) {
            selectedArticulos.removeIf(a -> a.getCodigo() != null && a.getCodigo().equals(articulo.getCodigo()));
        } else {
            selectedArticulos.add(articulo);
        }
    }

    public boolean isSelected(Articulos articulo) {
        for (Articulos a : selectedArticulos) {
            if (a.getCodigo() != null && a.getCodigo().equals(articulo.getCodigo())) {
                return true;
            }
        }
        return false;
    }

    public void selectAll() {
        selectedArticulos = new ArrayList<>(getFilteredArticulos());
    }

    public void clearSelection() {
        selectedArticulos.clear();
    }

    public String getContextPath() {
        return FacesContext.getCurrentInstance().getExternalContext().getRequestContextPath();
    }

    public int getTotalEtiquetas() {
        if (selectedArticulos == null) {
            return 0;
        }
        long countWithBarcode = selectedArticulos.stream()
                .filter(a -> a.getCodigoBarra() != null && !a.getCodigoBarra().trim().isEmpty())
                .count();
        return (int) (countWithBarcode * cantidadEtiquetas * cantidadCopias);
    }

    public List<Articulos> getSelectedArticulosWithBarcode() {
        if (selectedArticulos == null) {
            return new ArrayList<>();
        }
        return selectedArticulos.stream()
                .filter(a -> a.getCodigoBarra() != null && !a.getCodigoBarra().trim().isEmpty())
                .collect(Collectors.toList());
    }

    public void imprimirEtiquetas() {
        FacesContext.getCurrentInstance().addMessage(null,
            new FacesMessage(FacesMessage.SEVERITY_INFO,
                "Imprimir",
                "Use Ctrl+P o Cmd+P para imprimir las etiquetas. Configure la impresión para que ajuste a la página."));
    }
}
