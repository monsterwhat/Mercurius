package Controllers;

import Models.Articulos.Articulos;
import Models.Articulos.ArticuloStock;
import Models.Familia;
import Models.Departamento;
import Services.ArticulosService;
import Services.FamiliaService;
import Services.DepartamentoService;
import Services.InventarioService;
import jakarta.annotation.PostConstruct;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import lombok.Data;
import org.primefaces.util.LangUtils;

@Data
@Named(value = "InventarioResumenController")
@ViewScoped
public class InventarioResumenController implements Serializable {

    @Inject
    private ArticulosService articuloService;
    @Inject
    private FamiliaService familiaService;
    @Inject
    private DepartamentoService departamentoService;
    @Inject
    private InventarioService inventarioService;

    private List<Articulos> articulos;
    private List<Familia> familias;
    private List<Departamento> departamentos;
    private List<ArticuloStock> stocks;

    private String globalFilter;
    private Integer familiaID;
    private Integer departamentoID;

    @PostConstruct
    public void init() {
        loadData();
    }

    private void loadData() {
        familias = familiaService.listAll();
        departamentos = departamentoService.listAll();
        stocks = inventarioService.getAllStock();
    }

    public List<Articulos> getArticulos() {
        if (articulos == null) {
            articulos = articuloService.ListAllEnabled();
        }
        return articulos;
    }

    public BigDecimal getStockForArticulo(Articulos articulo) {
        if (stocks == null || articulo == null || articulo.getCodigoBarra() == null) {
            return BigDecimal.ZERO;
        }
        return stocks.stream()
                .filter(s -> s.getCodigoBarra().equals(articulo.getCodigoBarra()))
                .map(ArticuloStock::getStock)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    public String getStockStatus(Articulos articulo) {
        BigDecimal currentStock = getStockForArticulo(articulo);
        Integer stockOptimo = articulo.getStockOptimo();

        if (currentStock == null || currentStock.compareTo(BigDecimal.ZERO) <= 0) {
            return "Sin Stock";
        }

        if (stockOptimo == null || stockOptimo <= 0) {
            return "OK";
        }

        BigDecimal lowStockThreshold = new BigDecimal(stockOptimo).multiply(new BigDecimal("0.25"));

        if (currentStock.compareTo(lowStockThreshold) <= 0) {
            return "Stock Bajo";
        }

        return "OK";
    }

    public String getStockStatusStyle(Articulos articulo) {
        String status = getStockStatus(articulo);
        switch (status) {
            case "Sin Stock":
                return "status-out";
            case "Stock Bajo":
                return "status-low";
            default:
                return "status-ok";
        }
    }

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Articulos articulo = (Articulos) value;
        return String.valueOf(articulo.getCodigo()).contains(filterText)
                || (articulo.getNombre() != null && articulo.getNombre().toLowerCase().contains(filterText))
                || (articulo.getCodigoBarra() != null && articulo.getCodigoBarra().toLowerCase().contains(filterText))
                || (articulo.getFamilia() != null && articulo.getFamilia().getNombre() != null && articulo.getFamilia().getNombre().toLowerCase().contains(filterText))
                || (articulo.getDepartamento() != null && articulo.getDepartamento().getNombre() != null && articulo.getDepartamento().getNombre().toLowerCase().contains(filterText));
    }

    public long getTotalArticulos() {
        return articuloService.countActivos();
    }

    public long getCeroStockCount() {
        return getArticulos().stream()
                .filter(a -> getStockForArticulo(a).compareTo(BigDecimal.ZERO) == 0)
                .count();
    }

    public long getNegativoStockCount() {
        return getArticulos().stream()
                .filter(a -> getStockForArticulo(a).compareTo(BigDecimal.ZERO) < 0)
                .count();
    }

    public long getAllStockCount() {
        return getTotalArticulos();
    }

    public List<Articulos> getFilteredArticulos() {
        return getFilteredArticulosByType("all");
    }

    public List<Articulos> getFilteredArticulosCero() {
        return getFilteredArticulosByType("cero");
    }

    public List<Articulos> getFilteredArticulosNegativos() {
        return getFilteredArticulosByType("negativos");
    }

    private List<Articulos> getFilteredArticulosByType(String type) {
        List<Articulos> result = getArticulos();

        switch (type) {
            case "cero":
                result = result.stream()
                        .filter(a -> getStockForArticulo(a).compareTo(BigDecimal.ZERO) == 0)
                        .collect(Collectors.toList());
                break;
            case "negativos":
                result = result.stream()
                        .filter(a -> getStockForArticulo(a).compareTo(BigDecimal.ZERO) < 0)
                        .collect(Collectors.toList());
                break;
            default:
                break;
        }

        if (familiaID != null && familiaID > 0) {
            final Integer familiaIdFilter = familiaID;
            result = result.stream()
                    .filter(a -> a.getFamilia() != null && a.getFamilia().getId() == familiaIdFilter)
                    .collect(Collectors.toList());
        }

        if (departamentoID != null && departamentoID > 0) {
            final Integer deptIdFilter = departamentoID;
            result = result.stream()
                    .filter(a -> a.getDepartamento() != null && a.getDepartamento().getId() == deptIdFilter)
                    .collect(Collectors.toList());
        }

        if (globalFilter != null && !globalFilter.trim().isEmpty()) {
            String filterText = globalFilter.trim().toLowerCase();
            result = result.stream()
                    .filter(articulo -> globalFilterFunction(articulo, filterText, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        }

        return result;
    }

    public void clearFilters() {
        familiaID = null;
        departamentoID = null;
        globalFilter = null;
    }
}
