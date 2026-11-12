package Services;

import Models.ComprobantesEmitidos;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Persistencia del importador de facturas respaldadas por el sistema anterior.
 * La deduplicación es por {@code haciendaClave}: el ZIP puede traer documentos
 * que ya existen (migrados o emitidos por Mercurius) y se omiten.
 */
@ApplicationScoped
public class FacturaImportService {

    private static final Logger LOG = Logger.getLogger(FacturaImportService.class);

    @Inject
    EntityManager em;

    /** Claves ya presentes en la base; una sola consulta por carga. */
    @Nonnull
    public Set<String> clavesExistentes() {
        try {
            return new HashSet<>(em.createQuery(
                    "SELECT c.haciendaClave FROM ComprobantesEmitidos c "
                            + "WHERE c.haciendaClave IS NOT NULL", String.class)
                    .getResultList());
        } catch (PersistenceException e) {
            LOG.warn("Error leyendo claves de facturas emitidas: " + e.getMessage()
                    + " | source=FacturaImportService.clavesExistentes()");
            return Set.of();
        }
    }

    /**
     * Persiste un lote en su propia transacción. Si el lote falla, reintenta
     * uno por uno para no perder los documentos válidos por culpa de uno malo.
     */
    public int persistirLote(@Nonnull List<ComprobantesEmitidos> lote) {
        if (lote.isEmpty()) {
            return 0;
        }
        try {
            QuarkusTransaction.requiringNew().run(() -> lote.forEach(em::persist));
            return lote.size();
        } catch (RuntimeException e) {
            LOG.warn("Fallo el lote de importacion (" + lote.size()
                    + "), se reintenta documento por documento: " + e.getMessage()
                    + " | source=FacturaImportService.persistirLote()");
        }
        int ok = 0;
        for (ComprobantesEmitidos comprobante : lote) {
            try {
                QuarkusTransaction.requiringNew().run(() -> em.persist(comprobante));
                ok++;
            } catch (RuntimeException e) {
                LOG.warn("No se pudo importar la factura "
                        + (comprobante.getHaciendaClave() == null ? "?" : comprobante.getHaciendaClave())
                        + ": " + e.getMessage()
                        + " | source=FacturaImportService.persistirLote()");
            }
        }
        return ok;
    }
}
