package Services.Correos;

import Models.Correos.EmailTemplate;
import Services.GService;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import java.util.List;
import java.util.Map;

/**
 *
 * @author Al
 */
@ApplicationScoped
public class EmailTemplateService extends GService<EmailTemplate> {

    private static final Logger LOG = Logger.getLogger(EmailTemplateService.class);

    @Override
    @Nonnull
    protected Class<EmailTemplate> getEntityClass() {
        return EmailTemplate.class;
    }

    @Nullable
    public EmailTemplate findByNombre(@Nonnull String nombre) {
        try {
            TypedQuery<EmailTemplate> query = em.createQuery(
                "SELECT t FROM EmailTemplate t WHERE t.nombre = :nombre", EmailTemplate.class);
            query.setParameter("nombre", nombre);
            List<EmailTemplate> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
                        LOG.warn("Error finding template by name: " + e.getMessage() + " | source=" + "EmailTemplateService.findByNombre()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Nullable
    public EmailTemplate findActivoByTipo(@Nonnull String tipo) {
        try {
            TypedQuery<EmailTemplate> query = em.createQuery(
                "SELECT t FROM EmailTemplate t WHERE t.tipo = :tipo AND t.status = true ORDER BY t.fechaModificacion DESC", EmailTemplate.class);
            query.setParameter("tipo", tipo);
            query.setMaxResults(1);
            List<EmailTemplate> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);
        } catch (PersistenceException e) {
                        LOG.warn("Error finding active template by type: " + e.getMessage() + " | source=" + "EmailTemplateService.findActivoByTipo()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Nonnull
    public List<EmailTemplate> findByTipo(@Nonnull String tipo) {
        try {
            TypedQuery<EmailTemplate> query = em.createQuery(
                "SELECT t FROM EmailTemplate t WHERE t.tipo = :tipo ORDER BY t.nombre ASC", EmailTemplate.class);
            query.setParameter("tipo", tipo);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.warn("Error finding templates by type: " + e.getMessage() + " | source=" + "EmailTemplateService.findByTipo()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return List.of();
        }
    }

    /**
     * Procesa una plantilla reemplazando los placeholders {{variable}} con los valores del mapa.
     */
    @Nonnull
    public String procesarPlantilla(@Nonnull EmailTemplate template, @Nonnull Map<String, String> variables) {
        String html = template.getCuerpoHtml();
        if (html == null) {
            return "";
        }
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            html = html.replace("{{" + entry.getKey() + "}}", entry.getValue() != null ? entry.getValue() : "");
        }
        return html;
    }

    /**
     * Crea una plantilla solo si no existe una con el mismo nombre.
     * @return true si se creó, false si ya existía.
     */
    public boolean createIfNotExists(@Nonnull EmailTemplate template) {
        try {
            Long count = em.createQuery(
                "SELECT COUNT(t) FROM EmailTemplate t WHERE t.nombre = :nombre", Long.class)
                .setParameter("nombre", template.getNombre())
                .getSingleResult();

            if (count > 0) {
                return false;
            }
            em.persist(template);
            return true;
        } catch (PersistenceException e) {
                        LOG.warn("Error creating template: " + e.getMessage() + " | source=" + "EmailTemplateService.createIfNotExists()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }
}
