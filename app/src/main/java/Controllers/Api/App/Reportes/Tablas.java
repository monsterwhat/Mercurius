package Controllers.Api.App.Reportes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import Utils.FormatoVista;
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
        ordenar(filas, sortKey, dir, fila -> fila.get(sortKey));
    }

    /**
     * Same ordering contract over any row type.
     *
     * <p>Los reportes que calculan las filas en memoria (pronósticos) no
     * construyen 200k mapas de presentación: ordenan su lista compacta pasando
     * el valor de la columna, y solo materializan la página visible.</p>
     */
    static <T> void ordenar(@Nonnull List<T> filas,
                            @Nullable String sortKey,
                            @Nullable String dir,
                            @Nonnull Function<T, Object> valor) {
        if (sortKey == null || sortKey.isBlank() || filas.size() < 2) {
            return;
        }
        boolean desc = "desc".equalsIgnoreCase(dir);

        // La clave se calcula UNA vez por fila. Con Comparator.comparing el
        // extractor se reevalúa en cada comparación (n log n): ordenar 148k
        // pronósticos por nombre eran ~2,5M parseos y otros tantos
        // NumberFormatException, 13 s extra sobre la misma página.
        List<FilaOrdenada<T>> ordenadas = new ArrayList<>(filas.size());
        for (T fila : filas) {
            ordenadas.add(new FilaOrdenada<>(fila, ValorOrden.de(valor.apply(fila))));
        }
        Comparator<FilaOrdenada<T>> comparator =
                Comparator.comparing(ordenada -> ordenada.clave());
        ordenadas.sort(desc ? comparator.reversed() : comparator);
        for (int i = 0; i < ordenadas.size(); i++) {
            filas.set(i, ordenadas.get(i).fila());
        }
    }

    /** Fila con su clave de orden ya calculada (ordenamientos estables). */
    private record FilaOrdenada<T>(T fila, ValorOrden clave) {
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
            String limpio = text.replace("\u20A1", "")
                    .replace("\u00A0", "").replace("\u202F", "").replace(" ", "");
            // Los montos salen de FormatoVista en es-CR ("₡1.234,56" o
            // "₡1 234,56") y los conteos de fmtNumero en formato inglés
            // ("1,234.50"): el último separador decide cuál es el decimal y
            // el otro se descarta como agrupación.
            int ultimaComa = limpio.lastIndexOf(',');
            int ultimoPunto = limpio.lastIndexOf('.');
            if (ultimaComa >= 0 && ultimoPunto >= 0) {
                limpio = ultimaComa > ultimoPunto
                        ? limpio.replace(".", "").replace(',', '.')
                        : limpio.replace(",", "");
            } else if (ultimaComa >= 0) {
                limpio = limpio.replace(',', '.');
            }
            if (!esNumero(limpio)) {
                return new ValorOrden(null, text);
            }
            try {
                return new ValorOrden(new BigDecimal(limpio), null);
            } catch (NumberFormatException e) {
                return new ValorOrden(null, text);
            }
        }

        /**
         * Descarte barato antes de {@code new BigDecimal}: sobre texto (nombres
         * de artículo, departamentos) el constructor siempre lanza
         * NumberFormatException, y en una página de 148k filas esas excepciones
         * dominaban el tiempo de ordenamiento.
         */
        private static boolean esNumero(@Nonnull String texto) {
            int inicio = 0;
            if (!texto.isEmpty() && (texto.charAt(0) == '-' || texto.charAt(0) == '+')) {
                inicio = 1;
            }
            if (inicio >= texto.length()) {
                return false;
            }
            boolean punto = false;
            for (int i = inicio; i < texto.length(); i++) {
                char c = texto.charAt(i);
                if (c == '.') {
                    if (punto) {
                        return false;
                    }
                    punto = true;
                } else if (c < '0' || c > '9') {
                    return false;
                }
            }
            return true;
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

    /** ₡ #,##0,00 en es-CR (formato único de la aplicación). */
    @Nonnull
    static String fmtColones(@Nullable BigDecimal monto) {
        if (monto == null) {
            return "-";
        }
        return FormatoVista.moneda(monto);
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
