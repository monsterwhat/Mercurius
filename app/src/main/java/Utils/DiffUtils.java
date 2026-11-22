package Utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for capturing entity state snapshots and parsing diffs
 * for the activity log (Registros Internos).
 *
 * <p><b>Redaccion.</b> Las entidades hidratan relaciones como
 * {@code Usuarios}, que incluye el hash de la contrasena. El snapshot se usa en
 * logs y alertas, asi que un serializador con {@link BeanSerializerModifier}
 * elimina cualquier propiedad sensible antes de escribirla. Nunca se registra
 * un secreto ni un hash.</p>
 *
 * @author Al
 */
public final class DiffUtils {

    /** Nombres de propiedad que jamas deben aparecer en un snapshot. */
    private static final Set<String> PROPIEDADES_SENSIBLES = Set.of(
            "password", "contrasena", "contraseña", "certificadopassword",
            "haciendaapikey", "fidesauthpassword", "authsessionkey",
            "haciendaencryptionkey", "secret", "token", "jwt", "apikey",
            "apikeysecret", "sessionkey", "encryptionkey");

    private static final ObjectMapper MAPPER = crearMapper();

    private DiffUtils() {
    }

    private static ObjectMapper crearMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.setSerializerFactory(mapper.getSerializerFactory()
                .withSerializerModifier(new BeanSerializerModifier() {
                    @Override
                    public List<BeanPropertyWriter> changeProperties(
                            SerializationConfig config,
                            BeanDescription beanDesc,
                            List<BeanPropertyWriter> beanProperties) {
                        beanProperties.removeIf(propiedad ->
                                esSensible(propiedad.getName()));
                        return beanProperties;
                    }
                }));
        return mapper;
    }

    private static boolean esSensible(String nombre) {
        String limpio = nombre == null ? "" : nombre.toLowerCase(Locale.ROOT);
        return PROPIEDADES_SENSIBLES.contains(limpio)
                || limpio.contains("password")
                || limpio.contains("contrasena")
                || limpio.contains("secret")
                || limpio.contains("apikey")
                || limpio.contains("sessionkey")
                || limpio.contains("encryptionkey");
    }

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
