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
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.util.LangUtils;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "InventarioResumenController")
@ViewScoped
public class InventarioResumenController implements Serializable {

    @Inject @Nonnull
    private ArticulosService articuloService;
    @Inject @Nonnull
    private FamiliaService familiaService;
    @Inject @Nonnull
    private DepartamentoService departamentoService;
    @Inject @Nonnull
    private InventarioService inventarioService;

    @Nullable
    private List<Articulos> articulos;
    @Nonnull
    private List<Familia> familias;
    @Nonnull
    private List<Departamento> departamentos;
    @Nullable
    private List<ArticuloStock> stocks;

    @Nullable
    private String globalFilter;
    @Nullable
    private Integer familiaID;
    @Nullable
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

    public @Nonnull List<Articulos> getArticulos() {
        if (articulos == null) {
            articulos = articuloService.ListAllEnabled();
        }
        return articulos;
    }

    public @Nonnull BigDecimal getStockForArticulo(@Nullable Articulos articulo) {
        if (stocks == null || articulo == null || articulo.getCodigoBarra() == null) {
            return BigDecimal.ZERO;
        }
        return stocks.stream()
                .filter(s -> s.getCodigoBarra().equals(articulo.getCodigoBarra()))
                .map(ArticuloStock::getStock)
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    public @Nonnull String getStockStatus(@Nonnull Articulos articulo) {
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

    public @Nonnull String getStockStatusStyle(@Nonnull Articulos articulo) {
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

    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
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

    public @Nonnull List<Articulos> getFilteredArticulos() {
        return getFilteredArticulosByType("all");
    }

    public @Nonnull List<Articulos> getFilteredArticulosCero() {
        return getFilteredArticulosByType("cero");
    }

    public @Nonnull List<Articulos> getFilteredArticulosNegativos() {
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
