package Services;

import Models.CierreCaja;
import Models.Users;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import java.util.Date;
import java.util.List;

@Named
@ApplicationScoped
public class CierreCajaService extends GService<CierreCaja> {

    private static final Logger LOG = Logger.getLogger(CierreCajaService.class);

    @Override
    protected @Nonnull Class<CierreCaja> getEntityClass() {
        return CierreCaja.class;
    }

    @PostConstruct
    public void init() {
    }

    public @Nullable CierreCaja findSesionAbierta(@Nonnull Users usuario) {
        try {
            TypedQuery<CierreCaja> query = em.createQuery(
                "SELECT c FROM CierreCaja c WHERE c.usuario = :usuario AND c.estado = 'abierto'",
                CierreCaja.class
            );
            query.setParameter("usuario", usuario);
            query.setMaxResults(1);
            return query.getSingleResult();
        } catch (NoResultException e) {
            return null;
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error finding open session: " + e.getMessage() + " | user=" + String.valueOf(usuario) + " | source=" + "CierreCajaService.findSesionAbierta()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public @Nullable List<CierreCaja> listHistorial(@Nonnull Users usuario) {
        try {
            TypedQuery<CierreCaja> query = em.createQuery(
                "SELECT c FROM CierreCaja c WHERE c.usuario = :usuario ORDER BY c.fechaApertura DESC",
                CierreCaja.class
            );
            query.setParameter("usuario", usuario);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error listing session history: " + e.getMessage() + " | user=" + String.valueOf(usuario) + " | source=" + "CierreCajaService.listHistorial()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public @Nullable List<CierreCaja> listHistorialPorFecha(@Nonnull Users usuario, @Nonnull Date desde, @Nonnull Date hasta) {
        try {
            TypedQuery<CierreCaja> query = em.createQuery(
                "SELECT c FROM CierreCaja c WHERE c.usuario = :usuario AND c.fechaApertura BETWEEN :desde AND :hasta ORDER BY c.fechaApertura DESC",
                CierreCaja.class
            );
            query.setParameter("usuario", usuario);
            query.setParameter("desde", desde);
            query.setParameter("hasta", hasta);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error listing session history by date: " + e.getMessage() + " | user=" + String.valueOf(usuario) + " | source=" + "CierreCajaService.listHistorialPorFecha()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }
}
