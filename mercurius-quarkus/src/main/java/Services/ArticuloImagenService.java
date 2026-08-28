package Services;

import Models.Articulos.ArticuloImagen;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import java.util.Collections;
import java.util.List;

@Named
@ApplicationScoped
public class ArticuloImagenService extends GService<ArticuloImagen> {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(ArticuloImagenService.class.getName());

    @Override
    protected @Nonnull Class<ArticuloImagen> getEntityClass() {
        return ArticuloImagen.class;
    }

    public @Nullable ArticuloImagen findById(@Nonnull Long id) {
        try {
            return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error finding image: " + e.getMessage() + " | source=" + "ArticuloImagenService.findById()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    @Transactional
    public void deleteById(@Nonnull Long id) {
        try {
            ArticuloImagen entity = em.find(getEntityClass(), id);
            if (entity != null) {
                em.remove(entity);
            em.flush();
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error deleting image: " + e.getMessage() + " | source=" + "ArticuloImagenService.deleteById()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    public @Nonnull List<ArticuloImagen> findByArticuloCodigo(@Nonnull Long articuloCodigo) {
        try {
            TypedQuery<ArticuloImagen> query = em.createQuery(
                "SELECT i FROM ArticuloImagen i WHERE i.articulo.codigo = :codigo ORDER BY i.orden ASC",
                ArticuloImagen.class);
            query.setParameter("codigo", articuloCodigo);
            return query.getResultList();
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error listing images: " + e.getMessage() + " | source=" + "ArticuloImagenService.findByArticuloCodigo()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
            return Collections.emptyList();
        }
    }

    @Transactional
    public void updateOrden(@Nonnull Long imagenId, int nuevoOrden) {
        try {
            ArticuloImagen imagen = em.find(getEntityClass(), imagenId);
            if (imagen != null) {
                imagen.setOrden(nuevoOrden);
                em.merge(imagen);
            }
        } catch (PersistenceException e) {
                        LOG.log(java.util.logging.Level.WARNING, "Error updating image order: " + e.getMessage() + " | source=" + "ArticuloImagenService.updateOrden()" + " | antes=" + String.valueOf(null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Transactional
    public int getNextOrden(@Nonnull Long articuloCodigo) {
        try {
            TypedQuery<Integer> query = em.createQuery(
                "SELECT COALESCE(MAX(i.orden), -1) + 1 FROM ArticuloImagen i WHERE i.articulo.codigo = :codigo",
                Integer.class);
            query.setParameter("codigo", articuloCodigo);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            return 0;
        }
    }
}
