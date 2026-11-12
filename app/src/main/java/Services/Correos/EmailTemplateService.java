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

    /**
     * Carga el catálogo de plantillas predeterminadas (HTML con estilos en
     * línea, listo para Gmail/Outlook). Es idempotente: no duplica las que ya
     * existen por nombre.
     *
     * @return cuántas plantillas se crearon
     */
    @jakarta.transaction.Transactional
    public int createDefaults(@Nullable Models.Usuarios usuario) {
        // El usuario que llega del recurso suele estar detached (otra
        // transacción): se reengancha al contexto antes de asociarlo a las
        // plantillas o el persist falla con "detached entity".
        Models.Usuarios gestionado = usuario == null || usuario.getId() == null
                ? null : em.find(Models.Usuarios.class, usuario.getId());
        int creadas = 0;
        for (EmailTemplate template : defaults(gestionado)) {
            if (createIfNotExists(template)) {
                creadas++;
            }
        }
        LOG.info("Plantillas predeterminadas cargadas: " + creadas
                + " | user=" + (usuario == null ? null : usuario.getUsername())
                + " | source=EmailTemplateService.createDefaults()");
        return creadas;
    }

    @Nonnull
    private static java.util.List<EmailTemplate> defaults(@Nullable Models.Usuarios usuario) {
        java.util.List<EmailTemplate> lista = new java.util.ArrayList<>();
        lista.add(predeterminada("Factura electrónica", "Su factura {{consecutivo}}",
                "NOTIFICACIONES", """
                <h2 style="margin:0 0 14px;color:#0b3b8c;font-size:19px">Su factura está lista</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}}, adjuntamos la factura electrónica <strong>{{consecutivo}}</strong>
                  del {{fecha}} por un total de <strong>{{total}}</strong>.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#f8fafc;border:1px solid #e5e9f2;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#374151;font-size:14px;line-height:1.7">
                    <strong>Consecutivo:</strong> {{consecutivo}}<br>
                    <strong>Fecha:</strong> {{fecha}}<br>
                    <strong>Total:</strong> {{total}}
                  </td></tr>
                </table>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Los archivos XML y PDF van adjuntos a este correo.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver factura
                  </a>
                </p>
                """, usuario));
        lista.add(predeterminada("Alerta de stock", "Alerta de stock: {{cantidad}} artículos",
                "ALERTAS_STOCK", """
                <h2 style="margin:0 0 14px;color:#b45309;font-size:19px">Hay artículos por reponer</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola, el inventario reporta <strong>{{cantidad}}</strong> artículos por debajo del
                  mínimo configurado.
                </p>
                <table role="presentation" width="100%" cellpadding="0" cellspacing="0" border="0"
                       style="margin:18px 0;background:#fffbeb;border:1px solid #fde68a;border-radius:10px">
                  <tr><td style="padding:14px 18px;color:#92400e;font-size:14px;line-height:1.7">
                    {{detalle}}
                  </td></tr>
                </table>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#b45309;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Revisar inventario
                  </a>
                </p>
                """, usuario));
        lista.add(predeterminada("Reporte programado", "Su reporte {{reporte}}",
                "REPORTES", """
                <h2 style="margin:0 0 14px;color:#0b3b8c;font-size:19px">Reporte programado</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Adjuntamos el reporte <strong>{{reporte}}</strong> correspondiente al período
                  <strong>{{periodo}}</strong>.
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  Generado el {{fecha}}.
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Abrir reportes
                  </a>
                </p>
                """, usuario));
        lista.add(predeterminada("Notificación general", "{{asunto}}",
                "PERSONALIZADO", """
                <h2 style="margin:0 0 14px;color:#0b3b8c;font-size:19px">{{asunto}}</h2>
                <p style="margin:0 0 12px;color:#1f2937;font-size:15px;line-height:1.55">
                  Hola {{cliente}},
                </p>
                <p style="margin:0 0 18px;color:#1f2937;font-size:15px;line-height:1.55">
                  {{mensaje}}
                </p>
                <p style="margin:0">
                  <a href="{{enlace}}" style="display:inline-block;background:#0b3b8c;color:#ffffff;
                     text-decoration:none;padding:12px 22px;border-radius:8px;font-size:15px;font-weight:600">
                    Ver más
                  </a>
                </p>
                """, usuario));
        // Catálogos extensos por caso: comprobantes electrónicos y reportes.
        lista.addAll(EmailTemplateDefaultsComprobantes.templates(usuario));
        lista.addAll(EmailTemplateDefaultsReportes.templates(usuario));
        return lista;
    }

    @Nonnull
    private static EmailTemplate predeterminada(@Nonnull String nombre, @Nonnull String asunto,
                                                @Nonnull String tipo, @Nonnull String cuerpoHtml,
                                                @Nullable Models.Usuarios usuario) {
        EmailTemplate template = new EmailTemplate();
        template.setNombre(nombre);
        template.setAsunto(asunto);
        template.setTipo(tipo);
        template.setCuerpoHtml(cuerpoHtml.strip());
        template.setStatus(true);
        template.setFechaCreacion(new java.util.Date());
        template.setFechaModificacion(new java.util.Date());
        template.setUsuario(usuario);
        return template;
    }
}
