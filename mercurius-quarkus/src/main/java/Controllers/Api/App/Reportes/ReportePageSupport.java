package Controllers.Api.App.Reportes;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.core.HttpHeaders;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Shared server-side paging/sorting/fragment plumbing for the T20 read-only
 * reportes pages ({@code /app/reportes/*}).
 *
 * <p>Implements the server side of the {@code _kit/data-table} contract
 * (docs/ui-kit.md §3.1): the resource reads {@code page}/{@code size}/
 * {@code sort}/{@code dir} query params, computes {@code totalPages} and the
 * pager window server-side (Qute has no division), pages an in-memory list
 * (same semantics as the legacy PrimeFaces paginator over the full filtered
 * list) and re-emits filter values through the reserved-key-free
 * {@code params} map.</p>
 *
 * <p>It also centralizes the HTMX fragment detection: when the request
 * carries the {@code HX-Request: true} header the resources render only the
 * page template's {@code {#fragment id=tabla}} section instead of the whole
 * layout.</p>
 *
 * <p>Read-only helper — no state, no business logic.</p>
 */
public final class ReportePageSupport {

    /** HTMX injects this header on every boosted request (kit contract). */
    public static final String HX_REQUEST_HEADER = "HX-Request";

    /** Legacy PrimeFaces default rows-per-page on the migrated tables. */
    public static final int DEFAULT_SIZE = 20;

    /** Upper bound kept generous so parity tests can fetch everything at once. */
    private static final int MAX_SIZE = 500;

    /** Numbered-pager window size rendered by _kit/pagination. */
    private static final int PAGE_WINDOW = 5;

    private ReportePageSupport() {
    }

    /**
     * @return {@code true} when the request is an HTMX boosted call that must
     *         receive only the table fragment.
     */
    public static boolean isHxRequest(@Nullable HttpHeaders headers) {
        if (headers == null) {
            return false;
        }
        String value = headers.getHeaderString(HX_REQUEST_HEADER);
        return value != null && "true".equalsIgnoreCase(value.trim());
    }

    /** 1-based current page, clamped to {@code >= 1}. */
    public static int clampPage(@Nullable Integer page) {
        return (page == null || page < 1) ? 1 : page;
    }

    /** Page size clamped to {@code [1, MAX_SIZE]} with legacy default. */
    public static int clampSize(@Nullable Integer size) {
        if (size == null || size < 1) {
            return DEFAULT_SIZE;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** {@code ceil(total / size)} computed server-side (Qute has no division). */
    public static int totalPages(long total, int size) {
        if (total <= 0 || size <= 0) {
            return 1;
        }
        return (int) ((total + size - 1) / size);
    }

    /**
     * Centered window of page numbers for the pager
     * (e.g. page=4 of 10 → {@code [2,3,4,5,6]}), clipped to the bounds.
     */
    public static @Nonnull List<Integer> pageWindow(int page, int totalPages) {
        int half = PAGE_WINDOW / 2;
        int start = Math.max(1, page - half);
        int end = Math.min(totalPages, start + PAGE_WINDOW - 1);
        start = Math.max(1, end - PAGE_WINDOW + 1);
        List<Integer> window = new ArrayList<>();
        for (int i = start; i <= end; i++) {
            window.add(i);
        }
        return window;
    }

    /** In-memory slice matching the legacy PrimeFaces paginator behavior. */
    public static @Nonnull <T> List<T> pageOf(@Nonnull List<T> rows, int page, int size) {
        int from = (page - 1) * size;
        if (from >= rows.size() || from < 0) {
            return List.of();
        }
        int to = Math.min(from + size, rows.size());
        return rows.subList(from, to);
    }

    /**
     * Null-safe comparator for in-memory table sorting: nulls sort first,
     * direction flips by reversing the base comparator.
     */
    public static @Nonnull <T, U extends Comparable<U>> Comparator<T> sortBy(
            @Nonnull Function<T, U> extractor, boolean ascending) {
        Comparator<T> comparator = Comparator.comparing(extractor, Comparator.nullsFirst(Comparator.naturalOrder()));
        return ascending ? comparator : comparator.reversed();
    }

    /** Legacy {@code dir} query param contract: anything but "desc" sorts asc. */
    public static boolean isDescending(@Nullable String dir) {
        return "desc".equalsIgnoreCase(dir);
    }

    /**
     * Parses an ISO {@code yyyy-MM-dd} query param; {@code null}/blank/invalid
     * all yield {@code null} so callers can fall back to their legacy defaults.
     */
    public static @Nullable LocalDate parseDate(@Nullable String isoDate) {
        if (isoDate == null || isoDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(isoDate.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Builds the preserved-params map passed back down to the kit fragments.
     * Null/blank values are dropped and the RESERVED keys
     * {@code page,size,sort,dir} are rejected with an assertion-style guard so
     * a caller bug cannot corrupt the emitted links (kit golden rule #5).
     */
    public static @Nonnull Map<String, Object> params(Object... keyValuePairs) {
        return fill(new java.util.LinkedHashMap<>(), keyValuePairs, true, true);
    }

    /**
     * One kit data-table column descriptor. {@code key} is nullable for
     * non-sortable columns, so {@link Map#of} (null-hostile) must not be used.
     */
    public static @Nonnull Map<String, Object> columna(@Nonnull String label, @Nullable String key) {
        Map<String, Object> mapa = new java.util.LinkedHashMap<>();
        mapa.put("label", label);
        mapa.put("key", key);
        return mapa;
    }

    /**
     * Ordered template-data model for arbitrary sizes ({@code Map.of} caps at
     * ten pairs); null values are dropped so Qute {@code ??} guards decide.
     * Unlike {@link #params}, model keys may carry paging state (page/size are
     * legitimate model entries for the kit fragments).
     */
    public static @Nonnull Map<String, Object> model(Object... keyValuePairs) {
        return fill(new java.util.LinkedHashMap<>(), keyValuePairs, false, false);
    }

    private static @Nonnull Map<String, Object> fill(
            @Nonnull Map<String, Object> target, @Nonnull Object[] keyValuePairs,
            boolean rejectReservedKeys, boolean dropBlankValues) {
        for (int i = 0; i + 1 < keyValuePairs.length; i += 2) {
            String key = String.valueOf(keyValuePairs[i]);
            Object value = keyValuePairs[i + 1];
            if (value == null || (dropBlankValues && value instanceof String s && s.isBlank())) {
                continue;
            }
            if (rejectReservedKeys
                    && ("page".equals(key) || "size".equals(key) || "sort".equals(key) || "dir".equals(key))) {
                throw new IllegalArgumentException("Reserved query key must not be in params: " + key);
            }
            target.put(key, value);
        }
        return target;
    }
}
