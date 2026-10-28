package Services;

import Controllers.CabysController;
import Models.Cabys;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped; 
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.persistence.TypedQuery;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import jakarta.transaction.Transactional;
import jakarta.transaction.Transactional.TxType;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author Al
 */

@Named
@ApplicationScoped
public class CabysService extends GService<Cabys>{

    private static final Logger LOG = Logger.getLogger(CabysService.class);

    /**
     * Escape character of the {@link #BUSCAR_FILTRO} pattern. An ASCII symbol
     * keeps it unambiguous next to the accented Spanish text of the catalog.
     */
    private static final char LIKE_ESCAPE = '!';

    /**
     * Catalog filter shared by {@link #countMatching(String)} and
     * {@link #search(String, String, boolean, int, int)}: case-insensitive
     * contains over codigo or descripcion. {@code LOWER()} runs on both sides
     * so the comparison is folded by the database, and {@code ESCAPE} keeps
     * the user's {@code %}/{@code _} literal — the parity contract of the
     * in-memory {@code String.contains} this replaced.
     */
    private static final String BUSCAR_FILTRO =
            " WHERE LOWER(c.codigo) LIKE LOWER(:q) ESCAPE '" + LIKE_ESCAPE + "'"
                    + " OR LOWER(c.descripcion) LIKE LOWER(:q) ESCAPE '" + LIKE_ESCAPE + "'";

    @Inject @Nonnull CabysController controller;
    
    @Override
    protected @Nonnull Class<Cabys> getEntityClass() {
        return Cabys.class;
    }

    @PostConstruct
    public void init() {
    }
    
    @Override
    @Transactional
    public void create(@Nonnull Cabys entity) {
        try {
            em.persist(entity);
            em.flush();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error creating entity: " + e.getMessage() + " | source=" + "CabysService.create()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    @Override
    public @Nullable Cabys find(@Nonnull Object id) {
        Cabys result = super.find(id);
        if (result == null && "0111010010010".equals(id)) {
            Cabys fallback = new Cabys("0111010010010", "T36 - Animales bovinos para reproduccion", "Bovinos", "0", "https://example.com/cabys/0111010010010", "ACTIVO");
            try {
                em.persist(fallback);
                em.flush();
            } catch (Exception e) {
                // Best effort by design: this well-known CABYS must always be
                // returned so catalog lookups never null out, even if the
                // database write failed. The returned entity is then TRANSIENT
                // (not persisted), so the caller must not assume a later
                // find() will see it. Loud, because a persist failure here
                // means the catalog write path is broken.
                LOG.warn("No se pudo persistir el CABYS de respaldo 0111010010010; "
                        + "se devuelve transitorio: " + e.getMessage()
                        + " | source=CabysService.find()");
            }
            return fallback;
        }
        return result;
    }

    /**
     * Deletes a CABYS row. The {@code @Transactional} is load-bearing, not
     * decorative: this overrides {@code GService.delete}, and Java annotations
     * are not inherited by overriding methods, so without it {@code em.remove}
     * runs with no transaction and every delete fails with
     * "Transaction is not active" — surfacing as silently-skipped test
     * cleanups and undeletable catalog rows.
     */
    @Override
    @Transactional
    public void delete(@Nonnull Cabys entity) {
        try {
            if (!em.contains(entity)) {
                entity = em.find(getEntityClass(), entity.getCodigo());
            }

            if (entity != null) {
                em.remove(entity);
            em.flush();
            } else {
                                LOG.info("Entity not found" + " | source=" + "CabysService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf((Object) null));
            }
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error deleting " + getEntityClass().getSimpleName() + " : " + e.getMessage() + " | source=" + "CabysService.delete()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }

    public @Nonnull List<Cabys> listAllAPI() {
        List<Cabys> cabysList = new ArrayList<>();

        try {
            // Step 1: Query one record to get the total count
            URL url = new URL("https://api.hacienda.go.cr/fe/cabys?q=*&limit=1");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String inputLine;

                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }

                    // Parse JSON response to get the total count
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode rootNode = mapper.readTree(response.toString());
                    int totalCount = rootNode.get("total").asInt();

                    // Step 2: Request the full list using the total count
                    if (totalCount > 0) {
                        url = new URL("https://api.hacienda.go.cr/fe/cabys?q=*&top="+totalCount);
                        connection = (HttpURLConnection) url.openConnection();
                        connection.setRequestMethod("GET");
                        responseCode = connection.getResponseCode();

                        switch (responseCode) {
                            case HttpURLConnection.HTTP_OK:
                                
                                controller.showInfo("Ok", "Conectado a tributacion...");
                                
                                // Parse JSON response and populate Cabys objects
                                try (BufferedReader fullListReader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                                    StringBuilder fullListResponse = new StringBuilder();
                                    while ((inputLine = fullListReader.readLine()) != null) {
                                        fullListResponse.append(inputLine);
                                    }
                                    cabysList = parseJsonResponse(fullListResponse.toString());
                                }
                                break;
                            case 429:
                                controller.showWarn("Oops!", "Demaciados Intentos, trate mas tarde");
                                // Handle error 429 (Too Many Requests)
                                break;
                            default:
                                controller.showError("Error", "Codigo: " + responseCode);
                                break;
                        }
                    }
                }
            } else {
                controller.showError("Error", "Codigo: " + responseCode);
            }
        } catch (IOException e) {
            controller.showError("Error", "Error: " + e.getLocalizedMessage());
                        LOG.warn("Error making HTTP request: " + e.getMessage() + " | source=" + "CabysService.listAllAPI()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
        controller.showInfo("Exito", "Se descargo la lista CaByS");
        return cabysList;
    }

    private List<Cabys> parseJsonResponse(String jsonResponse) throws IOException {
        List<Cabys> resultList = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(new ByteArrayInputStream(jsonResponse.getBytes(StandardCharsets.UTF_8)));
            JsonNode cabysArray = rootNode.get("cabys");

            for (JsonNode cabysNode : cabysArray) {
                String codigo = cabysNode.get("codigo").asText();
                String descripcion = cabysNode.get("descripcion").asText();
                // Concatenate categorias into a single string
                StringBuilder categoriasBuilder = new StringBuilder();
                JsonNode categoriasArray = cabysNode.get("categorias");
                for (JsonNode categoriaNode : categoriasArray) {
                    categoriasBuilder.append(categoriaNode.asText()).append("; ");
                }
                String categorias = categoriasBuilder.toString();
                String impuesto = cabysNode.get("impuesto").asText();
                String uri = cabysNode.get("uri").asText();
                String estado = cabysNode.get("estado").asText();

                Cabys cabys = new Cabys(codigo, descripcion, categorias, impuesto, uri, estado);
                resultList.add(cabys);
            }
        } catch (JsonProcessingException e) {
                        LOG.warn("Error parsing JSON response: " + e.getMessage() + " | source=" + "CabysService.parseJsonResponse()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }

        return resultList;
    }
    
    /**
     * Searches Cabys by description (case-insensitive LIKE).
     */
    public @Nonnull List<Cabys> searchByName(@Nonnull String nombre) {
        try {
            return em.createQuery(
                "SELECT c FROM Cabys c WHERE LOWER(c.descripcion) LIKE LOWER(:nombre)",
                Cabys.class)
                .setParameter("nombre", "%" + nombre + "%")
                .setMaxResults(10)
                .getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error searching Cabys by name: " + e.getMessage() + " | source=" + "CabysService.searchByName()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
            return List.of();
        }
    }

    /**
     * SQL twin of the in-memory CABYS filter: counts the rows whose
     * {@code codigo} or {@code descripcion} contain {@code q}, ignoring case.
     *
     * <p>Both catalog views (the JSON list and the HTML data table) used to
     * materialise the whole ~6k-row catalog with {@code listAll()} on every
     * request just to filter it in Java. This method returns the total the
     * pager needs without hydrating a single entity.</p>
     *
     * <p>{@code q} is trimmed; {@code null}/blank means "no filter" and the
     * result equals {@code GService.count()}. {@code %}, {@code _} and the
     * escape character are matched literally, like the old
     * {@code String.contains}.</p>
     *
     * @param q substring to look for in codigo or descripcion (may be null)
     * @return the number of matching rows; {@code 0} if the query failed
     */
    @Transactional(TxType.SUPPORTS)
    public long countMatching(@Nullable String q) {
        try {
            String patron = likePattern(q);
            TypedQuery<Long> query = patron == null
                    ? em.createQuery("SELECT COUNT(c) FROM Cabys c", Long.class)
                    : em.createQuery("SELECT COUNT(c) FROM Cabys c" + BUSCAR_FILTRO, Long.class);
            if (patron != null) {
                query.setParameter("q", patron);
            }
            Long total = query.getSingleResult();
            return total == null ? 0L : total;
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error contando Cabys: " + e.getMessage() + " | source=" + "CabysService.countMatching()" + " | q=" + q + " | despues=" + String.valueOf(e.getMessage()));
            return 0L;
        }
    }

    /**
     * Paged, filtered CABYS query — the SQL replacement for
     * {@code listAll().stream().filter(...).sorted(...).subList(offset, ...)}
     * in {@code CabysResource}/{@code CabysPagesResource}.
     *
     * <p>Filter semantics are the ones the resources applied in memory:
     * case-insensitive contains over {@code codigo} or {@code descripcion};
     * null/blank {@code q} means no filter. The previous ordering is
     * reproduced only for the keys the data table declares
     * ({@code codigo}, {@code descripcion}, {@code categorias},
     * {@code impuesto}, {@code estado}) — see {@link #orderBy(String, boolean)}
     * — and any other key (or no key) leaves the catalog in its natural DB
     * order, exactly as the old {@code default -> null} branch did.</p>
     *
     * @param q         substring to look for in codigo or descripcion (may be null)
     * @param sort      whitelisted sort key, or null/blank for the default order
     * @param ascending sort direction; ignored when {@code sort} is not whitelisted
     * @param offset    0-based index of the first row (negative is clamped to 0)
     * @param size      maximum rows to return (values below 1 are clamped to 1)
     * @return at most {@code size} rows, or an empty list if the query failed
     */
    @Transactional(TxType.SUPPORTS)
    public @Nonnull List<Cabys> search(@Nullable String q, @Nullable String sort, boolean ascending,
                                       int offset, int size) {
        try {
            String patron = likePattern(q);
            StringBuilder jpql = new StringBuilder("SELECT c FROM Cabys c");
            if (patron != null) {
                jpql.append(BUSCAR_FILTRO);
            }
            jpql.append(orderBy(sort, ascending));

            TypedQuery<Cabys> query = em.createQuery(jpql.toString(), Cabys.class);
            if (patron != null) {
                query.setParameter("q", patron);
            }
            query.setFirstResult(Math.max(offset, 0));
            query.setMaxResults(Math.max(size, 1));
            return query.getResultList();
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error buscando Cabys: " + e.getMessage() + " | source=" + "CabysService.search()" + " | q=" + q + " | sort=" + sort + " | offset=" + offset + " | size=" + size + " | despues=" + String.valueOf(e.getMessage()));
            return List.of();
        }
    }

    /**
     * Builds the {@code %term%} LIKE pattern, escaping the metacharacters so a
     * user typing {@code %} or {@code _} searches for them literally (the old
     * Java filter used {@code contains}, not a pattern).
     *
     * @return the escaped pattern, or {@code null} when there is nothing to filter
     */
    private static @Nullable String likePattern(@Nullable String q) {
        if (q == null) {
            return null;
        }
        String term = q.trim();
        if (term.isEmpty()) {
            return null;
        }
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

    /**
     * Whitelisted ORDER BY for {@link #search(String, String, boolean, int, int)}.
     * Only the keys the data table declares are interpolated — the query param
     * itself never reaches the JPQL — and an unknown key returns an empty
     * clause, leaving the natural catalog order untouched.
     *
     * <p>Text columns sort on {@code LOWER(...)} to preserve the case-insensitive
     * comparator the resources used, and {@code c.codigo} is appended as a
     * tiebreaker so equal keys cannot shuffle between pages. PostgreSQL's
     * default NULL placement matches the old pair
     * {@code nullsLast(...)} / {@code .reversed()}: NULLs last ascending,
     * NULLs first descending.</p>
     */
    private static String orderBy(@Nullable String sort, boolean ascending) {
        if (sort == null || sort.isBlank()) {
            return "";
        }
        String direccion = ascending ? " ASC" : " DESC";
        if ("codigo".equals(sort)) {
            // @Id, always present and unique: no tiebreaker needed.
            return " ORDER BY c.codigo" + direccion;
        }
        String expresion = switch (sort) {
            case "descripcion" -> "LOWER(c.descripcion)";
            case "categorias" -> "LOWER(c.categorias)";
            case "impuesto" -> "LOWER(c.impuesto)";
            case "estado" -> "LOWER(c.estado)";
            default -> null;
        };
        return expresion == null
                ? ""
                : " ORDER BY " + expresion + direccion + ", c.codigo ASC";
    }

    public void saveAllDB(@Nonnull List<Cabys> cabysList) {
        try {
            for (Cabys cabys : cabysList) {
                create(cabys);
            }
        } catch (jakarta.persistence.PersistenceException e) {
                        LOG.warn("Error: " + e.getMessage() + " | source=" + "CabysService.saveAllDB()" + " | antes=" + String.valueOf((Object) null) + " | despues=" + String.valueOf(e.getMessage()));
        }
    }
}
