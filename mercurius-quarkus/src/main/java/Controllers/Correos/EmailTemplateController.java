package Controllers.Correos;

import Controllers.SessionController;
import Models.Correos.EmailTemplate;
import Models.Correos.EmailTemplateTipo;
import Services.AlertasService;
import Services.Correos.EmailTemplateService;
import Utils.DiffUtils;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import jakarta.faces.application.FacesMessage;
import jakarta.faces.context.FacesContext;
import jakarta.faces.view.ViewScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import jakarta.faces.model.SelectItem;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import org.primefaces.PrimeFaces;

/**
 *
 * @author Al
 */
@Getter @Setter @ToString @EqualsAndHashCode
@Named
@ViewScoped
public class EmailTemplateController implements Serializable {

    private static final Logger LOG = Logger.getLogger(EmailTemplateController.class.getName());

    @Inject @Nonnull EmailTemplateService emailTemplateService;
    @Inject @Nonnull private AlertasService alertasService;
    @Inject @Nonnull private SessionController currentSession;

    private List<EmailTemplate> templates;
    private List<EmailTemplate> filteredTemplates;
    private EmailTemplate selectedTemplate;
    private EmailTemplate newTemplate;
    private String previewHtml;
    private List<SelectItem> tipoOptions;
    private String templatesFilter;

    public EmailTemplateController() {
    }

    @PostConstruct
    public void init() {
        newTemplate = new EmailTemplate();
        selectedTemplate = null;
        templatesList();
        initTipoOptions();
        previewHtml = "";
    }

    private void initTipoOptions() {
        tipoOptions = new ArrayList<>();
        for (EmailTemplateTipo tipo : EmailTemplateTipo.values()) {
            tipoOptions.add(new SelectItem(tipo.name(), tipo.getDescripcion()));
        }
    }

    @Nonnull
    public List<EmailTemplate> templatesList() {
        if (templates == null) {
            templates = emailTemplateService.listAll();
        }
        return templates;
    }

    public long templatesCount() {
        return emailTemplateService.count();
    }

    public void openNewTemplate() {
        newTemplate = new EmailTemplate();
        newTemplate.setStatus(true);
        newTemplate.setFechaCreacion(new Date());
        newTemplate.setFechaModificacion(new Date());
        newTemplate.setUsuario(currentSession.getCurrentUser());
    }

    public void saveTemplate() {
        if (currentSession.isValid() && newTemplate != null) {
            var exists = emailTemplateService.findByNombre(newTemplate.getNombre());
            if (exists != null) {
                FacesContext.getCurrentInstance().addMessage(null,
                    new FacesMessage(FacesMessage.SEVERITY_WARN, "Ya existe una plantilla con ese nombre!", null));
                return;
            }
            
            newTemplate.setStatus(true);
            newTemplate.setFechaCreacion(new Date());
            newTemplate.setFechaModificacion(new Date());
            newTemplate.setUsuario(currentSession.getCurrentUser());
            emailTemplateService.create(newTemplate);
            
            alertasService.registrarAlerta("Plantilla de correo creada",
                "Se ha creado la plantilla de correo: " + newTemplate.getNombre(),
                currentSession.getCurrentUser(), 0, "EmailTemplateController.saveTemplate",
                null, DiffUtils.snapshotEntity(newTemplate));
            
            clearSelectedTemplate();
            PrimeFaces.current().executeScript("PF('CrearTemplateDialog').hide();");
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Plantilla creada exitosamente", null));
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }

    public void updateTemplate() {
        if (currentSession.isValid() && selectedTemplate != null) {
            String antes = DiffUtils.snapshotEntity(selectedTemplate);
            selectedTemplate.setFechaModificacion(new Date());
            emailTemplateService.update(selectedTemplate);
            
            alertasService.registrarAlerta("Plantilla de correo actualizada",
                "Se ha actualizado la plantilla de correo: " + selectedTemplate.getNombre(),
                currentSession.getCurrentUser(), 0, "EmailTemplateController.updateTemplate",
                antes, DiffUtils.snapshotEntity(selectedTemplate));
            
            clearSelectedTemplate();
            PrimeFaces.current().executeScript("PF('EditarTemplateDialog').hide();");
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Plantilla actualizada exitosamente", null));
        } else {
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_ERROR, "Sesion Invalida!", null));
        }
    }

    public void deleteTemplate() {
        if (selectedTemplate != null && currentSession.isValid()) {
            String antes = DiffUtils.snapshotEntity(selectedTemplate);
            emailTemplateService.delete(selectedTemplate);
            
            alertasService.registrarAlerta("Plantilla de correo eliminada",
                "Se ha eliminado la plantilla de correo: " + selectedTemplate.getNombre(),
                currentSession.getCurrentUser(), 0, "EmailTemplateController.deleteTemplate",
                antes, null);
            
            clearSelectedTemplate();
            FacesContext.getCurrentInstance().addMessage(null,
                new FacesMessage(FacesMessage.SEVERITY_INFO, "Plantilla eliminada exitosamente", null));
        }
    }

    public void toggleTemplate() {
        if (selectedTemplate != null) {
            selectedTemplate.setStatus(!selectedTemplate.isStatus());
            selectedTemplate.setFechaModificacion(new Date());
            emailTemplateService.update(selectedTemplate);
            
            String action = selectedTemplate.isStatus() ? "habilitada" : "deshabilitada";
            alertasService.registrarAlerta("Estado de plantilla cambiado",
                "Se ha " + action + " la plantilla de correo: " + selectedTemplate.getNombre(),
                currentSession.getCurrentUser(), 0, "EmailTemplateController.toggleTemplate",
                null, null);
        }
    }

    public void actualizarPreview() {
        if (selectedTemplate != null && selectedTemplate.getCuerpoHtml() != null) {
            previewHtml = emailTemplateService.procesarPlantilla(selectedTemplate, getSampleData());
        } else if (newTemplate != null && newTemplate.getCuerpoHtml() != null) {
            previewHtml = emailTemplateService.procesarPlantilla(newTemplate, getSampleData());
        } else {
            previewHtml = "";
        }
    }

    public String getPreviewHtml() {
        if (selectedTemplate != null && selectedTemplate.getCuerpoHtml() != null) {
            return emailTemplateService.procesarPlantilla(selectedTemplate, getSampleData());
        } else if (newTemplate != null && newTemplate.getCuerpoHtml() != null) {
            return emailTemplateService.procesarPlantilla(newTemplate, getSampleData());
        }
        return "";
    }

    public String getPreviewHtmlForTemplate(@Nonnull EmailTemplate template) {
        if (template.getCuerpoHtml() != null) {
            return emailTemplateService.procesarPlantilla(template, getSampleData());
        }
        return "";
    }

    private Map<String, String> getSampleData() {
        Map<String, String> sampleData = new HashMap<>();
        sampleData.put("nombre", "Reporte de Ejemplo");
        sampleData.put("fecha", "2026-01-15 10:30:00");
        sampleData.put("total", "150");
        sampleData.put("tabla", "<tr><td>Articulo A</td><td>₡12,500.00</td></tr><tr><td>Articulo B</td><td>₡8,750.00</td></tr>");
        sampleData.put("mensaje", "Adjunto encontrara el reporte de ejemplo.");
        sampleData.put("_empresa", "Mercurius");
        return sampleData;
    }

    public String getTipoLabel(String tipo) {
        if (tipo == null) return "";
        try {
            EmailTemplateTipo templateTipo = EmailTemplateTipo.valueOf(tipo);
            return templateTipo.getDescripcion();
        } catch (IllegalArgumentException e) {
            return tipo;
        }
    }

    public String getTipoBadgeClass(String tipo) {
        if (tipo == null) return "is-dark";
        switch (tipo) {
            case "REPORTES": return "is-info";
            case "ALERTAS_STOCK": return "is-danger";
            case "NOTIFICACIONES": return "is-warning";
            case "PERSONALIZADO": return "is-success";
            default: return "is-dark";
        }
    }

    public void clearSelectedTemplate() {
        templates = null;
        newTemplate = null;
        selectedTemplate = null;
        previewHtml = "";
    }

    @Nonnull
    public List<EmailTemplate> getFilteredTemplates() {
        if (templatesFilter != null && !templatesFilter.isEmpty()) {
            return templatesList().stream()
                .filter(t -> matchesFilter(t, templatesFilter))
                .collect(Collectors.toList());
        }
        return templatesList();
    }

    private boolean matchesFilter(@Nonnull EmailTemplate template, @Nonnull String filter) {
        String lowerFilter = filter.toLowerCase();
        return (template.getNombre() != null && template.getNombre().toLowerCase().contains(lowerFilter))
            || (template.getTipo() != null && template.getTipo().toLowerCase().contains(lowerFilter))
            || (template.getAsunto() != null && template.getAsunto().toLowerCase().contains(lowerFilter));
    }
}
