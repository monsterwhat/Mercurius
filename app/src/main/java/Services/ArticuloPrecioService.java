package Services;

import Models.Articulos.ArticuloPrecio;
import Models.Articulos.Articulos;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.util.List;
import org.jboss.logging.Logger;

@Named
@ApplicationScoped
public class ArticuloPrecioService extends GService<ArticuloPrecio> {

    private static final Logger LOG = Logger.getLogger(ArticuloPrecioService.class);

    @Override
    protected @Nonnull Class<ArticuloPrecio> getEntityClass() {
        return ArticuloPrecio.class;
    }

    @PostConstruct
    public void init() {
    }
    
    @Override
    public @Nullable List<ArticuloPrecio> listAll() {
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery("SELECT a FROM ArticuloPrecio a", ArticuloPrecio.class);
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error listing all entities: " + e.getMessage() + " | source=ArticuloPrecioService.listAll() | despues=" + e.getMessage());
            return null;
        }
    }
    
    public @Nullable ArticuloPrecio findByArticulo(@Nonnull Articulos articulo) {
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery("SELECT a FROM ArticuloPrecio a WHERE a.articulo = :articulo", ArticuloPrecio.class);
            query.setParameter("articulo", articulo);
            List<ArticuloPrecio> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList.get(resultList.size() - 1);
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error " + e.getLocalizedMessage() + " | source=ArticuloPrecioService.method() | despues=" + e.getMessage());
            return null;
        }    
    }
    
    public @Nullable List<ArticuloPrecio> findAllByArticulo(@Nonnull Articulos articulo) {
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery("SELECT a FROM ArticuloPrecio a WHERE a.articulo = :articulo", ArticuloPrecio.class);
            query.setParameter("articulo", articulo);
            List<ArticuloPrecio> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList;
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error " + e.getLocalizedMessage() + " | source=ArticuloPrecioService.method() | despues=" + e.getMessage());
            return null;
        }    
    }

    /**
     * Filter/join fragment shared by the price-history report count and page
     * queries. The report searches the same fields the legacy
     * globalFilterFunction covered: article name/barcode, department, family,
     * user and the row id.
     */
    private static final String REPORTE_JOINS =
            " FROM ArticuloPrecio p"
            + " LEFT JOIN p.articulo a"
            + " LEFT JOIN a.departamento d"
            + " LEFT JOIN a.familia f"
            + " LEFT JOIN p.usuario u";

    private static final String REPORTE_FILTER =
            " WHERE (:filtro IS NULL"
            + " OR LOWER(a.nombre) LIKE :filtro"
            + " OR LOWER(a.codigoBarra) LIKE :filtro"
            + " OR LOWER(d.nombre) LIKE :filtro"
            + " OR LOWER(f.nombre) LIKE :filtro"
            + " OR LOWER(u.username) LIKE :filtro"
            + " OR CAST(p.id AS String) LIKE :filtro)";

    /**
     * Counts the rows of the price-history report without hydrating them.
     *
     * <p>The table used to call {@link #listAll()} and filter/sort/page in
     * memory: 16k+ rows per request when the report opened. The filter is the
     * same, but it now runs in SQL so only the visible page is loaded.</p>
     *
     * @param filtro already-normalized {@code %needle%} pattern, or null
     */
    public long countReporte(@Nullable String filtro) {
        try {
            TypedQuery<Long> query = em.createQuery(
                    "SELECT COUNT(p)" + REPORTE_JOINS + REPORTE_FILTER, Long.class);
            query.setParameter("filtro", filtro);
            Long total = query.getSingleResult();
            return total == null ? 0L : total;
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error counting price report rows: " + e.getMessage()
                    + " | source=ArticuloPrecioService.countReporte() | despues=" + e.getMessage());
            return 0L;
        }
    }

    /**
     * Artículos que TIENEN historial de precios, filtrados por nombre o código.
     * Alimenta el selector del reporte: el operador busca "imperial" y elige el
     * artículo cuyo historial quiere ver, en vez de filtrar una tabla plana.
     */
    public @Nonnull List<Models.Articulos.Articulos> buscarArticulosConPrecio(
            @Nullable String filtro, int limite) {
        try {
            TypedQuery<Models.Articulos.Articulos> query = em.createQuery(
                    "SELECT DISTINCT a FROM ArticuloPrecio p JOIN p.articulo a "
                    + "WHERE LOWER(a.nombre) LIKE :filtro OR LOWER(a.codigoBarra) LIKE :filtro "
                    + "ORDER BY a.nombre", Models.Articulos.Articulos.class);
            query.setParameter("filtro", filtro == null || filtro.isBlank() ? "%%" : filtro);
            query.setMaxResults(Math.max(limite, 1));
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error buscando articulos con historial de precios: " + e.getMessage()
                    + " | source=ArticuloPrecioService.buscarArticulosConPrecio() | despues=" + e.getMessage());
            return List.of();
        }
    }

    /**
     * Para los artículos indicados, el id de su fila de precio MÁS RECIENTE
     * (fecha de compra, desempatando por id). Lo usa el reporte para marcar el
     * "Precio actual" de cada artículo.
     */
    public @Nonnull java.util.Map<Long, Integer> ultimoPrecioPorArticulo(
            @Nullable java.util.Collection<Long> codigos) {
        java.util.Map<Long, Integer> ultimo = new java.util.HashMap<>();
        if (codigos == null || codigos.isEmpty()) {
            return ultimo;
        }
        try {
            TypedQuery<Object[]> query = em.createQuery(
                    "SELECT p.id, p.fechaCompra, p.articulo.codigo FROM ArticuloPrecio p "
                    + "WHERE p.articulo.codigo IN :codigos", Object[].class);
            query.setParameter("codigos", codigos);
            java.util.Map<Long, java.util.Date> fechaUltimo = new java.util.HashMap<>();
            for (Object[] fila : query.getResultList()) {
                Integer id = (Integer) fila[0];
                java.util.Date fecha = (java.util.Date) fila[1];
                Long codigo = (Long) fila[2];
                if (codigo == null || id == null) {
                    continue;
                }
                Integer idActual = ultimo.get(codigo);
                if (idActual == null) {
                    ultimo.put(codigo, id);
                    fechaUltimo.put(codigo, fecha);
                    continue;
                }
                java.util.Date fechaActual = fechaUltimo.get(codigo);
                boolean masNuevo;
                if (fecha != null && fechaActual != null) {
                    int porFecha = fecha.compareTo(fechaActual);
                    masNuevo = porFecha > 0 || (porFecha == 0 && id >= idActual);
                } else if (fecha != null) {
                    masNuevo = true;
                } else if (fechaActual != null) {
                    masNuevo = false;
                } else {
                    masNuevo = id >= idActual;
                }
                if (masNuevo) {
                    ultimo.put(codigo, id);
                    fechaUltimo.put(codigo, fecha);
                }
            }
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error resolviendo el ultimo precio por articulo: " + e.getMessage()
                    + " | source=ArticuloPrecioService.ultimoPrecioPorArticulo() | despues=" + e.getMessage());
        }
        return ultimo;
    }

    /**
     * One page of the price-history report, filtered and sorted in SQL.
     *
     * <p>Relations used by the visible columns are fetch-joined so rendering
     * the page does not trigger a query per row. Sorting is numeric/date on
     * the real columns, not lexicographic on formatted strings.</p>
     */
    public @Nonnull List<ArticuloPrecio> findReportePage(
            @Nullable String filtro, @Nullable String sort, boolean ascending,
            int offset, int size) {
        String jpql = "SELECT p FROM ArticuloPrecio p"
                + " LEFT JOIN FETCH p.articulo a"
                + " LEFT JOIN FETCH a.departamento d"
                + " LEFT JOIN FETCH a.familia f"
                + " LEFT JOIN FETCH p.usuario u"
                + REPORTE_FILTER
                + " ORDER BY " + orderColumn(sort) + (ascending ? " ASC" : " DESC");
        try {
            TypedQuery<ArticuloPrecio> query = em.createQuery(jpql, ArticuloPrecio.class);
            query.setParameter("filtro", filtro);
            query.setFirstResult(Math.max(offset, 0));
            query.setMaxResults(Math.max(size, 1));
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error paging price report rows: " + e.getMessage()
                    + " | source=ArticuloPrecioService.findReportePage() | despues=" + e.getMessage());
            return List.of();
        }
    }

    /** Whitelisted sort keys: never interpolate a caller-provided string. */
    private static @Nonnull String orderColumn(@Nullable String sort) {
        return switch (sort == null ? "" : sort) {
            case "articulo" -> "a.nombre";
            case "porcentajeUtilidad" -> "p.porcentajeUtilidad";
            case "precioCostoSinIVA" -> "p.precioCostoSinIVA";
            case "precioConUtilidad" -> "p.precioConUtilidad";
            case "precioFinal" -> "p.precioFinal";
            case "fechaCompra" -> "p.fechaCompra";
            case "usuario" -> "u.username";
            // Por defecto el historial abre por la fecha del cambio: lo más
            // reciente arriba, que es el precio vigente del artículo.
            default -> "p.fechaCompra";
        };
    }

}
