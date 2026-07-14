package Utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility class for capturing entity state snapshots and parsing diffs
 * for the activity log (Registros Internos).
 *
 * @author Al
 */
public final class DiffUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private DiffUtils() {}

    /**
     * Captures the state of an entity as a JSON string before/after modification.
     * Returns null if serialization fails.
     */
    @Nullable
    public static String snapshotEntity(@Nullable Object entity) {
        if (entity == null) return null;
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(entity);
        } catch (JsonProcessingException e) {
            return entity.toString();
        }
    }

    /**
     * Parses two JSON snapshots and returns a map of field → [oldValue, newValue]
     * for fields that changed. Returns empty map if both are null or identical.
     */
    @Nonnull
    public static Map<String, String[]> parseDiff(@Nullable String antes, @Nullable String despues) {
        Map<String, String[]> diff = new LinkedHashMap<>();

        if (antes == null && despues == null) return diff;

        Map<String, Object> oldState = parseJson(antes);
        Map<String, Object> newState = parseJson(despues);

        if (oldState.isEmpty() && newState.isEmpty()) return diff;

        // Compare all keys from both states
        java.util.Set<String> allKeys = new java.util.LinkedHashSet<>(oldState.keySet());
        allKeys.addAll(newState.keySet());

        for (String key : allKeys) {
            String oldVal = formatValue(oldState.get(key));
            String newVal = formatValue(newState.get(key));

            if (!java.util.Objects.equals(oldVal, newVal)) {
                diff.put(key, new String[]{oldVal != null ? oldVal : "", newVal != null ? newVal : ""});
            }
        }

        return diff;
    }

    /**
     * Returns true if the entity has meaningful diff data (antes/despues are different).
     */
    public static boolean hasDiff(@Nullable String antes, @Nullable String despues) {
        if (antes == null && despues == null) return false;
        if (antes == null || despues == null) return true;
        return !antes.equals(despues);
    }

    @Nonnull
    private static Map<String, Object> parseJson(@Nullable String json) {
        if (json == null || json.isBlank()) return java.util.Map.of();
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = MAPPER.readValue(json, Map.class);
            return map != null ? map : java.util.Map.of();
        } catch (JsonProcessingException e) {
            // Not JSON — return as a single "value" key
            return java.util.Map.of("value", json);
        }
    }

    @Nullable
    private static String formatValue(@Nullable Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s.isEmpty() ? "(vacío)" : s;
        if (value instanceof Boolean b) return b ? "Sí" : "No";
        if (value instanceof Number n) return n.toString();
        if (value instanceof Map<?, ?> map) {
            // Nested object — try to extract a meaningful name or toString
            Object name = map.get("nombre");
            Object username = map.get("username");
            if (name != null) return name.toString();
            if (username != null) return username.toString();
            try {
                return MAPPER.writeValueAsString(map);
            } catch (JsonProcessingException e) {
                return map.toString();
            }
        }
        return value.toString();
    }
}
