package Controllers;

import Models.Registros.Alertas;
import Services.AlertasService;
import Utils.DiffUtils;
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
import java.util.Map;
import java.util.stream.Collectors;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.model.FilterMeta;
import org.primefaces.util.LangUtils;

@Getter @Setter @ToString @EqualsAndHashCode
@Named(value = "alertasController")
@ViewScoped
public class AlertasController implements Serializable {

    @Inject @Nonnull
    private AlertasService alertasService;

    @Nullable
    private List<Alertas> alertas;
    @Nullable
    private Alertas selectedAlerta;
    @Nullable
    private String alertasFilter;
    @Nonnull
    private List<FilterMeta> filterBy;

    @Nullable
    private Map<String, String[]> currentDiff;

    @PostConstruct
    public void init() {
        filterBy = new ArrayList<>();
    }

    @Nonnull
    public List<Alertas> getAlertas() {
        if (alertas == null) {
            alertas = alertasService.listAll();
        }
        if (alertasFilter != null && !alertasFilter.isEmpty()) {
            return alertas.stream()
                    .filter(alerta -> globalFilterFunction(alerta, alertasFilter, FacesContext.getCurrentInstance().getViewRoot().getLocale()))
                    .collect(Collectors.toList());
        }
        return alertas;
    }

    public void refreshAlertas() {
        alertas = alertasService.listAll();
    }

    public boolean globalFilterFunction(@Nonnull Object value, @Nullable Object filter, @Nonnull Locale locale) {
        String filterText = (filter == null) ? null : filter.toString().trim().toLowerCase();
        if (LangUtils.isBlank(filterText)) {
            return true;
        }

        Alertas alerta = (Alertas) value;
        String username = alerta.getUser() != null ? alerta.getUser().getUsername() : "Sistema";

        return String.valueOf(alerta.getCodigo()).contains(filterText) ||
               (alerta.getTimestamp() != null && alerta.getTimestamp().toString().contains(filterText)) ||
               (alerta.getTipo() != null && alerta.getTipo().toLowerCase().contains(filterText)) ||
               (alerta.getMensaje() != null && alerta.getMensaje().toLowerCase().contains(filterText)) ||
               username.toLowerCase().contains(filterText) ||
               (alerta.getSource() != null && alerta.getSource().toLowerCase().contains(filterText));
    }

    public void toggleVista(@Nonnull Alertas alerta) {
        alertasService.toggleVista(alerta);
        refreshAlertas();
    }

    public boolean hasDiff(@Nonnull Alertas alerta) {
        return DiffUtils.hasDiff(alerta.getAntes(), alerta.getDespues());
    }

    public void openDiff(@Nonnull Alertas alerta) {
        selectedAlerta = alerta;
        currentDiff = DiffUtils.parseDiff(alerta.getAntes(), alerta.getDespues());
    }

    @Nonnull
    public Map<String, String[]> getCurrentDiff() {
        return currentDiff != null ? currentDiff : java.util.Map.of();
    }

    @Nonnull
    public String fieldLabel(@Nonnull String fieldName) {
        return switch (fieldName) {
            case "codigo" -> "Código";
            case "nombre" -> "Nombre";
            case "codigoBarra" -> "Código de Barra";
            case "descripcion" -> "Descripción";
            case "UnidadMedida" -> "Unidad de Medida";
            case "unidadMedidaComercial" -> "Unidad Comercial";
            case "departamento" -> "Departamento";
            case "familia" -> "Familia";
            case "status" -> "Estado";
            case "processed" -> "Procesado";
            case "stockOptimo" -> "Stock Óptimo";
            case "diasStockSeguridad" -> "Días Stock Seguridad";
            case "estadoAlertas" -> "Alertas Habilitadas";
            case "fecha" -> "Fecha";
            case "usuario" -> "Usuario";
            case "contactoNombre" -> "Contacto";
            case "contactoTelefono" -> "Teléfono Contacto";
            case "contactoEmail" -> "Email Contacto";
            case "plazoPagoDias" -> "Plazo de Pago (días)";
            case "tiempoEntregaDias" -> "Tiempo de Entrega (días)";
            case "notas" -> "Notas";
            case "username" -> "Usuario";
            case "groupName" -> "Grupo";
            case "email" -> "Correo";
            case "id" -> "ID";
            case "value" -> "Valor";
            default -> fieldName;
        };
    }
}
