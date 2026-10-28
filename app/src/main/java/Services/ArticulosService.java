package Services;

import Models.Articulos.Articulos;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.TypedQuery;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jboss.logging.Logger;

@Named
@ApplicationScoped
public class ArticulosService extends GService<Articulos> {

    private static final Logger LOG = Logger.getLogger(ArticulosService.class);

    @Override
    protected @Nonnull Class<Articulos> getEntityClass() {
        return Articulos.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(@Nonnull Articulos entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.warn("Error creating entity: " + e.getMessage() + " | source=ArticulosService.create() | despues=" + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void delete(@Nonnull Articulos entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCodigo());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                LOG.info("Entity not found | source=ArticulosService.method()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=ArticulosService.delete() | despues=" + e.getMessage());
        }
    }

    @Override
    @Transactional
    public void update(@Nonnull Articulos entity) {
        try {
            em.merge(entity);
            em.flush();
        } catch (PersistenceException e) {
            LOG.warn("Error updating entity: " + e.getMessage() + " | source=ArticulosService.method() | despues=" + e.getMessage());
        }
    }
    
    @Override
    public @Nullable List<Articulos> listAll() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all entities: " + e.getMessage() + " | source=ArticulosService.listAll() | despues=" + e.getMessage());
            return null;
        }
    }
    
    @Transactional
    public void updateAndDisable(@Nonnull Articulos entity) {
        try {
            // Find the existing item by its ID
            Articulos existingItem = em.find(getEntityClass(), entity.getCodigo());

            if (existingItem != null) {
                // Disable the existing item
                existingItem.setStatus(false);
                existingItem.setProcessed(true);
                em.merge(existingItem);
            em.flush();

                // Create a new item with the updated information
                em.persist(entity);
            em.flush();
            } else {
                LOG.info("Entity not found | source=ArticulosService.method()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error updating entity: " + e.getMessage() + " | source=ArticulosService.method() | despues=" + e.getMessage());
        }
    }

    public @Nullable List<Articulos> ListAllEnabled() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.status = true", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all enabled entities: " + e.getMessage() + " | source=ArticulosService.method() | despues=" + e.getMessage());
            return null;
        }
    }

    @Transactional
    public void softDelete(@Nonnull Articulos entity) {
        try {
            // Find the item by its ID
            Articulos existingItem = em.find(getEntityClass(), entity.getCodigo());

            if (existingItem != null) {
                // Soft delete the item by setting its status to false
                existingItem.setStatus(false);
                em.merge(existingItem);
            em.flush();
            } else {
                LOG.info("Entity not found | source=ArticulosService.method()");
            }
        } catch (PersistenceException e) {
            LOG.warn("Error soft deleting entity: " + e.getMessage() + " | source=ArticulosService.softDelete() | despues=" + e.getMessage());
        }
    }
    
    public @Nullable Articulos findById(@Nonnull Integer id) {
    try {
        return em.find(getEntityClass(), id);
        } catch (PersistenceException e) {
            LOG.warn("Error finding entity by ID: " + e.getMessage() + " | source=ArticulosService.findById() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nullable Articulos findByName(@Nonnull String name) {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.nombre = :name", Articulos.class);
            query.setParameter("name", name);
            List<Articulos> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList.get(0);
        } catch (PersistenceException e) {
            LOG.warn("Error " + e.getLocalizedMessage() + " | source=ArticulosService.method() | despues=" + e.getMessage());
            return null;
        }
    }

    public @Nonnull List<Articulos> findByNameContaining(@Nonnull String name) {
        try {
            TypedQuery<Articulos> query = em.createQuery(
                "SELECT a FROM Articulos a WHERE a.processed = true AND a.status = true AND LOWER(a.nombre) LIKE LOWER(CONCAT('%', :name, '%'))",
                Articulos.class);
            query.setParameter("name", name);
            query.setMaxResults(20);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error searching by name: " + e.getMessage() + " | source=ArticulosService.findByNameContaining() | despues=" + e.getMessage());
            return List.of();
        }
    }

    public @Nullable Articulos findByBarCode(@Nonnull String barcode) {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.codigoBarra = :barcode", Articulos.class);
            query.setParameter("barcode", barcode);
            List<Articulos> resultList = query.getResultList();
            return resultList.isEmpty() ? null : resultList.get(0);
        } catch (PersistenceException e) {
            LOG.warn("Error " + e.getLocalizedMessage() + " | source=ArticulosService.method() | despues=" + e.getMessage());
            return null;
        }    
    }

    public @Nullable List<Articulos> listAllSinProcesar() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.processed = false AND a.status = true", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all enabled entities: " + e.getMessage() + " | source=ArticulosService.method() | despues=" + e.getMessage());
            return null;
        }    
    }

    public @Nullable List<Articulos> listAllActivosYProcesados() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.processed = true AND a.status = true", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all enabled entities: " + e.getMessage() + " | source=ArticulosService.method() | despues=" + e.getMessage());
            return null;
        }     
    }
    
    public @Nullable List<Articulos> listAllInactivos() {
        try {
            TypedQuery<Articulos> query = em.createQuery("SELECT a FROM Articulos a WHERE a.status = false", Articulos.class);
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing all enabled entities: " + e.getMessage() + " | source=ArticulosService.method() | despues=" + e.getMessage());
            return null;
        }     
    }

    /**
     * Escape character for the free-text {@code LIKE} patterns. Same convention
     * as {@code CabysService}: {@code %}/{@code _} are escaped so {@code q}
     * keeps the literal {@code contains} semantics of the old in-memory filter.
     */
    private static final char LIKE_ESCAPE = '!';

    /**
     * SQL-paged article list for the four article tabs, replacing the
     * "load every row, filter/sort/slice in memory" path. The WHERE clause is
     * the exact predicate of the tab's legacy listing:
     *
     * <ul>
     *   <li>{@code activos} (default) → {@code a.status = true}</li>
     *   <li>{@code inactivos} → {@code a.status = false}</li>
     *   <li>{@code catalogo} / {@code procesados} →
     *       {@code a.processed = true AND a.status = true}</li>
     *   <li>{@code pendientes} →
     *       {@code a.processed = false AND a.status = true}</li>
     * </ul>
     *
     * <p>{@code q} is matched case-insensitively over exactly the fields the
     * legacy {@code globalFilterFunction} used: codigo (as text), nombre,
     * codigoBarra, departamento.nombre, familia.nombre and usuario.username.
     * {@code descripcion}/{@code cabys} are NOT part of the filter.</p>
     *
     * <p>ORDER BY is whitelisted ({@code codigo}, {@code nombre},
     * {@code codigoBarra}, {@code cabys}, {@code precio}); unknown/null keys
     * fall back to codigo, and every sort ends with {@code a.codigo ASC} so a
     * page can never shuffle between requests. {@code precio} orders by the
     * latest price row (highest id), the same element {@code getLastPrecio()}
     * exposes. PostgreSQL's default NULL placement (ASC last / DESC first)
     * mirrors the old {@code nullsLast(...).reversed()} comparators.</p>
     *
     * @param tab    tab key as normalized by the resource ({@code activos},
     *               {@code inactivos}, {@code catalogo}, {@code pendientes})
     * @param q      free-text filter; null/blank means "no filter"
     * @param sort   whitelisted sort key; null/unknown uses codigo
     * @param dir    {@code desc} (case-insensitive) for descending, else asc
     * @param offset zero-based first row
     * @param size   page size (clamped to at least 1)
     */
    public @Nonnull List<Articulos> pageByTab(@Nullable String tab, @Nullable String q,
                                              @Nullable String sort, @Nullable String dir,
                                              int offset, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        boolean filtered = hasFilterText(q);
        String sortKey = trimKey(sort);
        String jpql = "SELECT a"
                + fromClause(filtered, "cabys".equals(sortKey), "precio".equals(sortKey))
                + whereClause(tab, q, params)
                + orderBy(sort, dir);
        try {
            TypedQuery<Articulos> query = em.createQuery(jpql, Articulos.class);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                query.setParameter(param.getKey(), param.getValue());
            }
            query.setFirstResult(Math.max(offset, 0));
            query.setMaxResults(Math.max(size, 1));
            return query.getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error paging articles by tab: " + e.getMessage()
                    + " | source=ArticulosService.pageByTab()"
                    + " | tab=" + tab + " | q=" + q + " | sort=" + sort + " | dir=" + dir
                    + " | offset=" + offset + " | size=" + size
                    + " | despues=" + e.getMessage());
            return List.of();
        }
    }

    /**
     * COUNT companion of {@link #pageByTab}: identical FROM/WHERE (so the pager
     * total and the returned page can never disagree).
     */
    public long countByTab(@Nullable String tab, @Nullable String q) {
        Map<String, Object> params = new LinkedHashMap<>();
        String jpql = "SELECT COUNT(a)"
                + fromClause(hasFilterText(q), false, false)
                + whereClause(tab, q, params);
        try {
            TypedQuery<Long> query = em.createQuery(jpql, Long.class);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                query.setParameter(param.getKey(), param.getValue());
            }
            Long total = query.getSingleResult();
            return total == null ? 0L : total;
        } catch (PersistenceException e) {
            LOG.warn("Error counting articles by tab: " + e.getMessage()
                    + " | source=ArticulosService.countByTab()"
                    + " | tab=" + tab + " | q=" + q
                    + " | despues=" + e.getMessage());
            return 0L;
        }
    }

    /**
     * Shared FROM builder: to-one joins, so no row multiplication. The
     * {@code latest}/{@code lp} pair is only added for the price sort: one
     * grouped pass over the price history (max id per article) instead of a
     * correlated subquery evaluated per article.
     */
    private static String fromClause(boolean withFilterJoins, boolean withCabysJoin,
                                     boolean withPriceJoin) {
        StringBuilder from = new StringBuilder(" FROM Articulos a");
        if (withFilterJoins) {
            from.append(" LEFT JOIN a.departamento d")
                    .append(" LEFT JOIN a.familia f")
                    .append(" LEFT JOIN a.usuario u");
        }
        if (withCabysJoin) {
            from.append(" LEFT JOIN a.codigoCabys cb");
        }
        if (withPriceJoin) {
            from.append(" LEFT JOIN (SELECT p.articulo AS art, MAX(p.id) AS maxId")
                    .append(" FROM ArticuloPrecio p GROUP BY p.articulo) latest")
                    .append(" ON latest.art = a")
                    .append(" LEFT JOIN ArticuloPrecio lp ON lp.id = latest.maxId");
        }
        return from.toString();
    }

    /**
     * Shared WHERE builder for {@link #pageByTab} and {@link #countByTab}.
     * Tab keys match the normalized resource keys; anything unknown behaves
     * like {@code activos} (the resource's default). {@code q} goes into
     * {@code params} — user text is never concatenated into the JPQL.
     */
    private static String whereClause(@Nullable String tab, @Nullable String q,
                                      @Nonnull Map<String, Object> params) {
        List<String> predicates = new ArrayList<>();
        String tabKey = trimKey(tab);
        predicates.add(switch (tabKey == null ? "" : tabKey.toLowerCase(Locale.ROOT)) {
            case "inactivos" -> "a.status = false";
            case "catalogo", "procesados" -> "(a.processed = true AND a.status = true)";
            case "pendientes" -> "(a.processed = false AND a.status = true)";
            default -> "a.status = true";
        });
        String term = q == null ? "" : q.trim();
        if (!term.isEmpty()) {
            String like = "LIKE LOWER(:q) ESCAPE '" + LIKE_ESCAPE + "'";
            predicates.add("(LOWER(CAST(a.codigo AS string)) " + like
                    + " OR LOWER(a.nombre) " + like
                    + " OR LOWER(a.codigoBarra) " + like
                    + " OR LOWER(d.nombre) " + like
                    + " OR LOWER(f.nombre) " + like
                    + " OR LOWER(u.username) " + like + ")");
            params.put("q", likePattern(term));
        }
        return " WHERE " + String.join(" AND ", predicates);
    }

    /**
     * Whitelisted ORDER BY for {@link #pageByTab}. Only the keys the data table
     * declares are interpolated; the request parameter never reaches the JPQL.
     * Text keys sort on {@code LOWER(...)} to preserve the old
     * case-insensitive comparators, and {@code a.codigo ASC} is the
     * deterministic tiebreak. An unknown key (or none) leaves codigo order.
     */
    private static String orderBy(@Nullable String sort, @Nullable String dir) {
        String key = trimKey(sort);
        String direction = "desc".equalsIgnoreCase(dir) ? " DESC" : " ASC";
        if (key == null) {
            return " ORDER BY a.codigo ASC";
        }
        String expression = switch (key) {
            case "codigo" -> "a.codigo";
            case "nombre" -> "LOWER(a.nombre)";
            case "codigoBarra" -> "LOWER(a.codigoBarra)";
            case "cabys" -> "LOWER(cb.codigo)";
            // Latest price row (highest id), the element getLastPrecio()
            // exposes; joined once in fromClause instead of a per-row subquery.
            case "precio" -> "lp.precioFinal";
            default -> null;
        };
        if (expression == null) {
            return " ORDER BY a.codigo ASC";
        }
        return "codigo".equals(key)
                ? " ORDER BY " + expression + direction
                : " ORDER BY " + expression + direction + ", a.codigo ASC";
    }

    private static boolean hasFilterText(@Nullable String q) {
        return q != null && !q.trim().isEmpty();
    }

    @Nullable
    private static String trimKey(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Builds the {@code %term%} LIKE pattern, escaping {@code !}, {@code %} and
     * {@code _} so a user typing them searches literally (the old filter used
     * {@code String.contains}, not a pattern).
     */
    private static @Nonnull String likePattern(@Nonnull String term) {
        StringBuilder pattern = new StringBuilder(term.length() + 2);
        pattern.append('%');
        for (int i = 0; i < term.length(); i++) {
            char ch = term.charAt(i);
            if (ch == LIKE_ESCAPE || ch == '%' || ch == '_') {
                pattern.append(LIKE_ESCAPE);
            }
            pattern.append(ch);
        }
        pattern.append('%');
        return pattern.toString();
    }

    public @Nonnull List<Articulos> findArticulosAfterDate(@Nonnull Date fecha) {
        CriteriaBuilder cb = em.getCriteriaBuilder();
        CriteriaQuery<Articulos> cq = cb.createQuery(Articulos.class);
        Root<Articulos> articulos = cq.from(Articulos.class);

        Predicate datePredicate = cb.greaterThan(articulos.get("fecha"), fecha);
        cq.where(datePredicate);

        TypedQuery<Articulos> query = em.createQuery(cq);
        return query.getResultList();
    }

    public @Nonnull Long countActivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Articulos e WHERE e.status = true", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage() + " | source=ArticulosService.count() | despues=" + e.getMessage());
            return 0l;
        }
    }
    
    public @Nonnull Long countInactivos() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Articulos e WHERE e.status = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage() + " | source=ArticulosService.count() | despues=" + e.getMessage());
            return 0l;
        }
    }
    
    public @Nonnull Long countPendientes() {
        try {
            TypedQuery<Long> query = em.createQuery("SELECT COUNT(e) FROM Articulos e WHERE e.status = true AND e.processed = false", Long.class);
            return query.getSingleResult();
        } catch (PersistenceException e) {
            LOG.warn("Error counting "+ getEntityClass().getSimpleName() +" : " + e.getLocalizedMessage() + " | source=ArticulosService.count() | despues=" + e.getMessage());
            return 0l;
        }
    }

    /**
     * Filter-picker projection for the report pages: article name, family and
     * department, without hydrating the entity graph.
     *
     * <p>The histórico-de-ventas report used to call {@link #listAll()} just to
     * build three option lists: 6k entities (plus their to-one relations) per
     * request for a few hundred distinct strings. This projection returns the
     * same three columns and lets the caller deduplicate in a set.</p>
     *
     * @return rows of {@code [nombre, familiaNombre, departamentoNombre]},
     *         never null; a failed query returns an empty list (logged)
     */
    @SuppressWarnings("unchecked")
    public @Nonnull List<Object[]> listReporteFilterOptions() {
        try {
            return em.createQuery(
                    "SELECT a.nombre, f.nombre, d.nombre FROM Articulos a"
                    + " LEFT JOIN a.familia f"
                    + " LEFT JOIN a.departamento d")
                    .getResultList();
        } catch (PersistenceException e) {
            LOG.warn("Error listing report filter options: " + e.getLocalizedMessage()
                    + " | source=ArticulosService.listReporteFilterOptions() | despues=" + e.getMessage());
            return List.of();
        }
    }

}
