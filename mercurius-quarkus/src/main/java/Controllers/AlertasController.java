package Controllers;

import Models.Registros.Alertas;
import Services.AlertasService;
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
import org.primefaces.util.LangUtils;

@Data
@Named(value = "alertasController")
@ViewScoped
public class AlertasController implements Serializable {

    @Inject
    private AlertasService alertasService;

    private List<Alertas> alertas;
    private Alertas selectedAlerta;
    private String alertasFilter;
    private List<FilterMeta> filterBy;

    @PostConstruct
    public void init() {
        filterBy = new ArrayList<>();
    }

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

    public boolean globalFilterFunction(Object value, Object filter, Locale locale) {
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

    public void toggleVista(Alertas alerta) {
        alertasService.toggleVista(alerta);
        refreshAlertas();
    }
}
