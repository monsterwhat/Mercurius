package Services;

import Models.Clientes;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Named
@ApplicationScoped
public class ClientService extends GService<Clientes> {

    private static final Logger LOG = Logger.getLogger(ClientService.class);

    @Override
    protected @Nonnull Class<Clientes> getEntityClass() {
        return Clientes.class;
    }

    @PostConstruct
    public void init() {
    }

    @Override
    @Transactional
    public void create(@Nonnull Clientes entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error creating entity: " + e.getMessage() + " | source=" + "ClientService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public void delete(@Nonnull Clientes entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCode());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                                LOG.info("Entity not found" + " | source=" + "ClientService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "ClientService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
    
    public @Nullable List<Clientes> searchByName(@Nonnull String name) {
        try {
            TypedQuery<Clientes> query = em.createQuery(
                "SELECT c FROM Clientes c WHERE LOWER(c.name) LIKE LOWER(:name)", Clientes.class);
            query.setParameter("name", "%" + name + "%");
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error searching clients by name: " + e.getMessage() + " | source=" + "ClientService.searchByName()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return null;
        }
    }

    public boolean checkClientName(@Nonnull String username) {
        try {
            TypedQuery<Clientes> query = em.createQuery(
                "SELECT c FROM Clientes c WHERE LOWER(c.name) = LOWER(:username)", Clientes.class);
            query.setParameter("username", username);

            List<Clientes> resultList = query.getResultList();

            return !resultList.isEmpty();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error getting client by username: " + e.getMessage() + " | source=" + "ClientService.checkClientName()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }

    /**
     * Check if a client with the given tax ID (idNumber) already exists.
     * Case-sensitive match since tax IDs are canonical.
     */
    public boolean checkClientByIdNumber(@Nonnull String idNumber) {
        try {
            TypedQuery<Long> query = em.createQuery(
                "SELECT COUNT(c) FROM Clientes c WHERE c.idNumber = :idNumber", Long.class);
            query.setParameter("idNumber", idNumber);
            return query.getSingleResult() > 0;
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error checking client by ID number: " + e.getMessage() + " | source=" + "ClientService.checkClientByIdNumber()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return false;
        }
    }

    // ── P3 §3.4 step 4: SQL-side counting / paging / zone aggregation ────────
    //
    // ClientsResource used to walk every Clientes row (listAll()) for the page
    // header counters, the /zonas directory and the table window. The three
    // methods below keep the exact same observable contract (JSON keys, totals,
    // pager, empty state) while letting PostgreSQL do the count / GROUP BY /
    // LIMIT OFFSET work.

    /** Counters for the clientes page header: total, active and inactive. */
    public record StatusCounts(long total, long activos, long inactivos) {}

    /** One row of {@link #zoneUsage()}: a zone code and how many clients live in it. */
    public record ZoneUsage(int zoneCode, long clientes) {}

    /**
     * Whitelist of the table's sortable columns. The value is the JPQL
     * expression, never user input, so {@code sort} can only select one of
     * these — SQL injection through {@code ORDER BY} is impossible.
     *
     * <p>Strings are ordered through {@code LOWER(...)} to mirror the legacy
     * {@code String.CASE_INSENSITIVE_ORDER}; {@code status} intentionally has
     * no {@code LOWER} (it is a Boolean).</p>
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "code", "c.code",
            "name", "LOWER(c.name)",
            "address", "LOWER(c.address)",
            "email", "LOWER(c.email)",
            "idNumber", "LOWER(c.idNumber)",
            "phoneNumber", "LOWER(c.phoneNumber)",
            "status", "c.status");

    /**
     * Total / active / inactive client counts in ONE query ({@code GROUP BY
     * status}, so the numbers come from the same snapshot and
     * {@code activos + inactivos == total} always holds). A NULL
     * {@code status} counts as inactive, exactly like the legacy in-memory
     * pass ({@code todos.size() - activos}).
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull StatusCounts countByStatus() {
        try {
            List<Object[]> rows = em.createQuery(
                    "SELECT c.status, COUNT(c) FROM Clientes c GROUP BY c.status",
                    Object[].class).getResultList();
            long total = 0L;
            long activos = 0L;
            for (Object[] row : rows) {
                long count = row[1] == null ? 0L : ((Number) row[1]).longValue();
                total += count;
                if (Boolean.TRUE.equals(row[0])) {
                    activos += count;
                }
            }
            return new StatusCounts(total, activos, total - activos);
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error counting clients by status: " + e.getMessage()
                    + " | source=" + "ClientService.countByStatus()"
                    + " | antes=" + String.valueOf((Object) null)
                    + " | despues=" + String.valueOf(e.getMessage()));
            return new StatusCounts(0L, 0L, 0L);
        }
    }

    /**
     * Zone directory: {@code zoneCode} → number of clients, ordered by zone
     * code ascending (the same order the legacy {@code TreeMap} produced).
     * Replaces the full client scan that fed {@code /api/app/clientes/zonas}.
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<ZoneUsage> zoneUsage() {
        try {
            List<Object[]> rows = em.createQuery(
                    "SELECT c.zoneCode, COUNT(c) FROM Clientes c GROUP BY c.zoneCode ORDER BY c.zoneCode",
                    Object[].class).getResultList();
            List<ZoneUsage> usage = new ArrayList<>(rows.size());
            for (Object[] row : rows) {
                usage.add(new ZoneUsage(((Number) row[0]).intValue(), ((Number) row[1]).longValue()));
            }
            return usage;
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error grouping clients by zone: " + e.getMessage()
                    + " | source=" + "ClientService.zoneUsage()"
                    + " | antes=" + String.valueOf((Object) null)
                    + " | despues=" + String.valueOf(e.getMessage()));
            return List.of();
        }
    }

    /**
     * SQL-paged client list: case-insensitive name contains ({@code q}),
     * optional {@code estado} filter ({@code activo}/{@code inactivo}, any
     * other value is ignored like the legacy in-memory filter), whitelisted
     * ORDER BY and {@code setFirstResult}/{@code setMaxResults}.
     *
     * @param q      name fragment; blank/null means "no name filter"
     * @param estado {@code activo}, {@code inactivo} or anything else (ignored)
     * @param sort   one of {@link #SORT_COLUMNS}; null/unknown falls back to code
     * @param dir    {@code desc} (case-insensitive) for descending, else ascending
     * @param offset zero-based first row
     * @param size   page size (clamped to at least 1)
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<Clientes> pageByNameLike(@Nullable String q, @Nullable String estado,
                                                  @Nullable String sort, @Nullable String dir,
                                                  int offset, int size) {
        Map<String, Object> params = new LinkedHashMap<>();
        String jpql = "SELECT c FROM Clientes c"
                + whereClause(q, estado, params)
                + " ORDER BY " + orderBy(sort, dir);
        try {
            TypedQuery<Clientes> query = em.createQuery(jpql, Clientes.class);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                query.setParameter(param.getKey(), param.getValue());
            }
            query.setFirstResult(Math.max(offset, 0));
            query.setMaxResults(Math.max(size, 1));
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error paging clients by name/estado: " + e.getMessage()
                    + " | source=" + "ClientService.pageByNameLike()"
                    + " | antes=" + String.valueOf((Object) null)
                    + " | despues=" + String.valueOf(e.getMessage()));
            return List.of();
        }
    }

    /** Plan signature without the {@code estado} filter (used by the JSON list). */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<Clientes> pageByNameLike(@Nullable String q, @Nullable String sort,
                                                  @Nullable String dir, int offset, int size) {
        return pageByNameLike(q, null, sort, dir, offset, size);
    }

    /**
     * COUNT companion of {@link #pageByNameLike}: same WHERE clause, so the
     * pager total and the returned page can never disagree.
     */
    @Transactional(TxType.SUPPORTS)
    public long countByNameLike(@Nullable String q, @Nullable String estado) {
        Map<String, Object> params = new LinkedHashMap<>();
        String jpql = "SELECT COUNT(c) FROM Clientes c" + whereClause(q, estado, params);
        try {
            TypedQuery<Long> query = em.createQuery(jpql, Long.class);
            for (Map.Entry<String, Object> param : params.entrySet()) {
                query.setParameter(param.getKey(), param.getValue());
            }
            Long total = query.getSingleResult();
            return total == null ? 0L : total;
        } catch (jakarta.persistence.PersistenceException e) {
            LOG.warn("Error counting clients by name/estado: " + e.getMessage()
                    + " | source=" + "ClientService.countByNameLike()"
                    + " | antes=" + String.valueOf((Object) null)
                    + " | despues=" + String.valueOf(e.getMessage()));
            return 0L;
        }
    }

    /**
     * Shared WHERE builder for the paged list and its COUNT.
     *
     * <p>{@code q} is matched against the client NAME only (legacy
     * {@code searchByName} contract) with {@code LOWER(...) LIKE LOWER(...)};
     * {@code %}/{@code _} are NOT escaped, so they keep acting as SQL
     * wildcards exactly like before. Parameters go into {@code params} so no
     * user text is ever concatenated into the query string.</p>
     */
    private static String whereClause(@Nullable String q, @Nullable String estado,
                                      @Nonnull Map<String, Object> params) {
        List<String> predicates = new ArrayList<>();
        String term = q == null ? "" : q.trim();
        if (!term.isEmpty()) {
            predicates.add("LOWER(c.name) LIKE LOWER(:q)");
            params.put("q", "%" + term + "%");
        }
        String filtro = estado == null ? "" : estado.trim().toLowerCase(Locale.ROOT);
        if ("activo".equals(filtro)) {
            predicates.add("c.status = true");
        } else if ("inactivo".equals(filtro)) {
            predicates.add("(c.status = false OR c.status IS NULL)");
        }
        return predicates.isEmpty() ? "" : " WHERE " + String.join(" AND ", predicates);
    }

    /**
     * Whitelisted ORDER BY. PostgreSQL's default NULL placement matches the
     * legacy comparators: ASC puts NULLs last ({@code nullsLast}), DESC puts
     * them first (the reverse of {@code nullsLast(...)}). Ties fall back to
     * {@code c.code} so a page is deterministic.
     */
    private static String orderBy(@Nullable String sort, @Nullable String dir) {
        String column = sort == null ? null : SORT_COLUMNS.get(sort.trim());
        if (column == null) {
            return "c.code ASC";
        }
        String direction = "desc".equalsIgnoreCase(dir) ? "DESC" : "ASC";
        return "code".equals(sort.trim())
                ? column + " " + direction
                : column + " " + direction + ", c.code ASC";
    }
}
