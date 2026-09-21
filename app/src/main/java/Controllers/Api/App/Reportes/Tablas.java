package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Shared thin helpers for the T19 read-only report pages
 * ({@code /app/reportes/**}).
 *
 * <p>Everything here is presentation plumbing for the {@code _kit/data-table}
 * server-side contract (docs/ui-kit.md §3.1): in-memory paging over service
 * results, page-window computation (Qute has no division), row maps with
 * pre-formatted display values, and reserved-key-safe filter param maps.
 * NO business logic lives here — all numbers come from the existing Services.</p>
 */
final class Tablas {

    /** Reserved query keys emitted by _kit/data-table itself (never filters). */
    static final List<String> RESERVED_KEYS = List.of("page", "size", "sort", "dir");

    private static final DecimalFormat COLONES =
            new DecimalFormat("\u00A4#,##0.00");
    static {
        // Legacy f:convertNumber type="currency" currencySymbol="₡" rendering.
        java.text.DecimalFormatSymbols symbols =
                new java.text.DecimalFormatSymbols(java.util.Locale.ROOT);
        symbols.setCurrencySymbol("\u20A1");
        COLONES.setDecimalFormatSymbols(symbols);
    }

    private Tablas() {
    }

    /** Immutable ordered key/value builder for row and header maps. */
    @Nonnull
    static Map<String, Object> fila(@Nonnull Object... pares) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pares.length; i += 2) {
            map.put(String.valueOf(pares[i]), pares[i + 1]);
        }
        return map;
    }

    /** ceil(total/size), computed server-side per the kit contract. */
    static int totalPaginas(long total, int size) {
        if (size <= 0) {
            return 1;
        }
        return (int) Math.max(1L, (total + size - 1L) / size);
    }

    /** 1-based window of at most five page numbers centered on the current one. */
    @Nonnull
    static List<Integer> ventanaPaginas(int page, int totalPages) {
        int actual = Math.min(Math.max(page, 1), Math.max(totalPages, 1));
        int desde = Math.max(1, actual - 2);
        int hasta = Math.min(totalPages, desde + 4);
        desde = Math.max(1, hasta - 4);
        List<Integer> paginas = new ArrayList<>();
        for (int p = desde; p <= hasta; p++) {
            paginas.add(p);
        }
        return paginas;
    }

    /** In-memory window; page/size are clamped to sane bounds. */
    @Nonnull
    static <T> List<T> paginaDe(@Nonnull List<T> source, int page, int size) {
        int s = Math.min(Math.max(size, 1), 200);
        int p = Math.max(page, 1);
        int from = Math.min((p - 1) * s, source.size());
        int to = Math.min(from + s, source.size());
        return new ArrayList<>(source.subList(from, to));
    }

    /**
     * Sorts display rows by a column key. Nulls last; values are compared as
     * BigDecimals when both sides parse as numbers, otherwise as strings →
     * rows are pre-formatted display maps, so this mirrors what the user sees.
     */
    static void ordenar(@Nonnull List<Map<String, Object>> filas,
                        @Nullable String sortKey, @Nullable String dir) {
        if (sortKey == null || sortKey.isBlank()) {
            return;
        }
        boolean desc = "desc".equalsIgnoreCase(dir);
        Comparator<Map<String, Object>> comparator =
                Comparator.comparing(fila -> ValorOrden.de(fila.get(sortKey)));
        filas.sort(desc ? comparator.reversed() : comparator);
    }

    /**
     * Uniform comparable wrapper so numbers and text never mix under one
     * comparator type (numbers order before text, nulls last).
     */
    private static final class ValorOrden implements Comparable<ValorOrden> {

        private static final ValorOrden NULO = new ValorOrden(null, null);

        private final BigDecimal numero;
        private final String texto;

        private ValorOrden(BigDecimal numero, String texto) {
            this.numero = numero;
            this.texto = texto;
        }

        static ValorOrden de(Object value) {
            if (value == null) {
                return NULO;
            }
            if (value instanceof BigDecimal bigDecimal) {
                return new ValorOrden(bigDecimal, null);
            }
            if (value instanceof Number number) {
                return new ValorOrden(BigDecimal.valueOf(number.doubleValue()), null);
            }
            String text = String.valueOf(value).trim();
            if (text.isEmpty()) {
                return NULO;
            }
            String limpio = text.replace(",", "").replace("\u20A1", "");
            try {
                return new ValorOrden(new BigDecimal(limpio), null);
            } catch (NumberFormatException e) {
                return new ValorOrden(null, text);
            }
        }

        @Override
        public int compareTo(ValorOrden otro) {
            if (numero != null && otro.numero != null) {
                return numero.compareTo(otro.numero);
            }
            if (numero != null) {
                return -1;
            }
            if (otro.numero != null) {
                return 1;
            }
            if (texto != null && otro.texto != null) {
                return texto.compareToIgnoreCase(otro.texto);
            }
            if (texto != null) {
                return -1;
            }
            if (otro.texto != null) {
                return 1;
            }
            return 0;
        }
    }

    /**
     * Filter params preserved through sort/pager links. Reserved kit keys
     * (page/size/sort/dir) and blank values are dropped per the kit contract.
     */
    @Nonnull
    static Map<String, Object> params(@Nullable Map<String, String> filtros) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (filtros == null) {
            return params;
        }
        for (Map.Entry<String, String> entry : filtros.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key != null && !key.isBlank() && value != null && !value.isBlank()
                    && !RESERVED_KEYS.contains(key)) {
                params.put(key, value);
            }
        }
        return params;
    }

    /** Parses an ISO yyyy-MM-dd input into a Date; null when blank/invalid. */
    @Nullable
    static Date fecha(@Nullable String iso, boolean finDeDia) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            LocalDate localDate = LocalDate.parse(iso.trim());
            if (finDeDia) {
                return Date.from(localDate.atTime(23, 59, 59)
                        .atZone(ZoneId.systemDefault()).toInstant());
            }
            return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** dd/MM/yyyy HH:mm for movement timestamps (legacy convertDateTime parity). */
    @Nonnull
    static String fmtFechaHora(@Nullable Date date) {
        if (date == null) {
            return "-";
        }
        return new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm").format(date);
    }

    /** dd/MM/yyyy for plain dates. */
    @Nonnull
    static String fmtFecha(@Nullable Date date) {
        if (date == null) {
            return "-";
        }
        return new java.text.SimpleDateFormat("dd/MM/yyyy").format(date);
    }

    /** ₡ #,##0.00 (legacy f:convertNumber type="currency" currencySymbol="₡"). */
    @Nonnull
    static String fmtColones(@Nullable BigDecimal monto) {
        if (monto == null) {
            return "-";
        }
        return COLONES.format(monto);
    }

    /** Plain #,##0.00 (legacy f:convertNumber pattern="#,##0.00"). */
    @Nonnull
    static String fmtNumero(@Nullable BigDecimal numero) {
        if (numero == null) {
            return "-";
        }
        return new java.text.DecimalFormat("#,##0.00").format(numero);
    }

    /** Null-safe BigDecimal extraction used while building display rows. */
    @Nonnull
    static BigDecimal nuloACero(@Nullable BigDecimal valor) {
        return valor != null ? valor : BigDecimal.ZERO;
    }
}
