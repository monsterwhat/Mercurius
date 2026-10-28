package Utils;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nonnull;
import org.jboss.logging.Logger;

/**
 * Serializer for the shared report-chart payload (P2 Phase 2.1).
 *
 * <p>Resources hand a {@link Models.DTO.ReportChartPayload} (or any bean) to
 * {@link #toScriptJson(Object)} and put the returned string in the model; the
 * template renders it inside {@code <script type="application/json">} with
 * {@code .raw}. No template builds JSON by concatenation any more.</p>
 *
 * <p>The result is safe to inline in a {@code <script>} element: every
 * {@code <}, {@code >} and {@code &} is written as a {@code \}{@code uXXXX}
 * escape, plus U+2028/U+2029 (illegal in a JavaScript string literal before
 * ES2019 and still a parsing hazard in old engines). A product name such as
 * {@code O'Brien "x" </script>} therefore cannot close the element early even
 * though the payload is rendered raw.</p>
 *
 * <p>{@link JsonGenerator.Feature#WRITE_BIGDECIMAL_AS_PLAIN} makes chart values
 * come out as JSON numbers ({@code 1200.00}) instead of scientific notation
 * ({@code 1.2E+3}).</p>
 */
public final class ReportChartJson {

    private static final Logger LOG = Logger.getLogger(ReportChartJson.class);

    /**
     * Valid, minimal payload returned when serialization fails. It has no
     * series, so {@code web/report-chart.js} falls through to the card's empty
     * state instead of throwing on a {@code null} document.
     */
    private static final String EMPTY_PAYLOAD =
            "{\"type\":\"bar\",\"labels\":[],\"series\":[],\"legend\":false}";

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    private ReportChartJson() {
    }

    /**
     * Serialize {@code payload} as JSON that is safe to inline in a
     * {@code <script type="application/json">} element.
     *
     * @param payload bean/record to serialize; must not be null
     * @return the escaped JSON, or {@link #EMPTY_PAYLOAD} when serialization
     *         fails (logged as an error, never silent)
     */
    @Nonnull
    public static String toScriptJson(@Nonnull Object payload) {
        try {
            return escapeForScript(MAPPER.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            // A serialization failure means the chart cannot be drawn; log it
            // with the payload identity so the page can be traced, and return a
            // shape that drives the visible empty state instead of a broken one.
            LOG.errorf(e, "No se pudo serializar el payload de gráfica (%s); se envía uno vacío",
                    payload.getClass().getName());
            return EMPTY_PAYLOAD;
        }
    }

    /**
     * Escape the characters that can terminate or corrupt an inline
     * {@code <script>} block. {@code \}{@code uXXXX} is still the same string
     * once {@code JSON.parse} decodes it, so escaping is lossless for consumers.
     */
    @Nonnull
    static String escapeForScript(@Nonnull String json) {
        return json
                .replace("&", "\\u0026")
                .replace("<", "\\u003c")
                .replace(">", "\\u003e")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }
}
