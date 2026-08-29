package Services;

import Models.NotaCredito;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;

@Named
@ApplicationScoped
public class NotaCreditoService extends GService<NotaCredito> {

    private static final Logger LOG = Logger.getLogger(NotaCreditoService.class);

    @Override
    protected Class<NotaCredito> getEntityClass() {
        return NotaCredito.class;
    }

    @PostConstruct
    public void init() {
    }

    public List<NotaCredito> listPorComprobante(Long comprobanteId) {
        try {
            TypedQuery<NotaCredito> query = em.createQuery(
                "SELECT n FROM NotaCredito n WHERE n.comprobanteOriginal.id = :comprobanteId ORDER BY n.fecha DESC",
                NotaCredito.class
            );
            query.setParameter("comprobanteId", comprobanteId);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error listing credit notes: " + e.getMessage() + " | source=" + "NotaCreditoService.listPorComprobante()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public List<NotaCredito> listAll() {
        try {
            TypedQuery<NotaCredito> query = em.createQuery(
                "SELECT n FROM NotaCredito n ORDER BY n.fecha DESC",
                NotaCredito.class
            );
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error listing all credit notes: " + e.getMessage() + " | source=" + "NotaCreditoService.listAll()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
}
