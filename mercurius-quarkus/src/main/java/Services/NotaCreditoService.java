package Services;

import Models.NotaCredito;
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
            alertasService.registrarAlerta("Error", "Error listing credit notes: " + e.getMessage(), null, 0, "NotaCreditoService.listPorComprobante()", null, e.getMessage());
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
            alertasService.registrarAlerta("Error", "Error listing all credit notes: " + e.getMessage(), null, 0, "NotaCreditoService.listAll()", null, e.getMessage());
            return null;
        }
    }
}
