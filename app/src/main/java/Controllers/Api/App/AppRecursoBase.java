package Controllers.Api.App;

import io.quarkus.qute.TemplateInstance;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Utilidades compartidas por los recursos REST de {@code /app} (Qute + HTMX).
 *
 * <p><b>Objetivo.</b> Eliminar la copia-péguelo de helpers privados
 * {@code htmlOk}, {@code hxRedirect}, {@code col}, {@code pageWindow},
 * {@code orEmpty}, {@code emptyToNull}, {@code contains} y
 * {@code parseIntOrNull} que cada recurso de {@code Controllers.Api.App}
 * volver a declarar. Aquí viven como {@code public static} y cada recurso las
 * toma con {@code import static Controllers.Api.App.AppRecursoBase.*;}.</p>
 *
 * <p><b>Por qué {@code final} + import static y no una clase abstracta.</b> Una
 * base abstracta obligaría a migrar todos los recursos a la vez: un helper
 * {@code private static} en el hijo choca (no se puede <em>reducir</em> la
 * visibilidad de un miembro estático heredado) con el {@code protected}/
 * {@code public static} del padre, así que la migración parcial no compila.
 * Con esta utilidad no hay herencia: no hay nada que chocar.</p>
 *
 * <p><b>Regla de sombreado que hace la migración posible en cualquier orden.</b>
 * En Java, un miembro declarado en el cuerpo de la clase <em>oculta por
 * nombre</em> (shadowing) a cualquier miembro del mismo nombre traído por un
 * {@code static import on demand}, aunque las firmas no coincidan. Por eso:</p>
 * <ul>
 *   <li>un recurso que <b>conserva</b> su copia privada sigue compilando y
 *       sigue usando su propia versión (no cambia el comportamiento);</li>
 *   <li>un recurso que <b>borra</b> su copia pasa a usar la de aquí.</li>
 * </ul>
 * <p>Es decir, cada archivo se migra de forma independiente y en cualquier
 * orden; el resultado intermedio siempre compila. La única condición para
 * migrar un archivo es borrar <em>todas</em> sus copias locales cuyo nombre
 * exista en esta clase, para que ninguna quede ocultando a la compartida.</p>
 *
 * <p><b>Casi-aciertos deliberadamente NO movidos</b> (verificados archivo por
 * archivo; se dejan intactos porque no son intercambiables):</p>
 * <ul>
 *   <li>{@code orEmpty}: existen <b>tres</b> cuerpos incompatibles. Solo se
 *       movió {@code list == null ? Collections.emptyList() : list}. Se
 *       dejaron {@code list != null ? list : new ArrayList<>()} (devuelve una
 *       lista mutable) y {@code list == null ? List.of() : list} (inmutable),
 *       además de las variantes tipadas {@code List<ComprobantesEmitidos>} /
 *       {@code List<ComprobantesRecibidos>}: no son la misma función.</li>
 *   <li>{@code pageWindow}: {@code FacturasRecibidasResource} implementa una
 *       ventana centrada de 5 páginas, no el {@code page ± 2} de los demás.</li>
 *   <li>{@code windowOf}: devuelve un {@code private record Window} declarado
 *       dentro de cada recurso; no se puede compartir.</li>
 *   <li>{@code matches}: el nombre se reutiliza para sobrecargas por entidad
 *       ({@code Clientes}, {@code ComprobantesEmitidos},
 *       {@code ComprobantesRecibidos}, {@code ReporteProgramado},
 *       {@code Usuarios}). Mover solo la variante {@code String} ocultaría a
 *       las demás; se usa {@link #contains(String, String)} en su lugar.</li>
 *   <li>{@code parseIntOrNull}: la copia de aquí no lleva {@code @Nullable} en
 *       el método; ArticuloResource, CategoriaResource e InventarioResource
 *       conservan la suya anotada (mismo cuerpo). Hace sombra igual que los
 *       demás casos mixtos y compila sin cambios.</li>
 *   <li>{@code isHxRequest}, {@code currentUser}, {@code isAdmin}: son
 *       <b>de instancia</b> y tienen cuerpos distintos según si leen
 *       {@code RoutingContext}, {@code HttpHeaders} o
 *       {@code ReportePageSupport.isHxRequest}.</li>
 *   <li>{@code notFound}, {@code badRequest}, {@code serverError}: cada nombre
 *       acumula sobrecargas con cuerpos y tipos de medio distintos (por ejemplo
 *       {@code serverError} en {@code EtiquetasResource} fija un media type
 *       extra). Unificar cualquiera de ellos cambiaría el comportamiento de
 *       alguna respuesta.</li>
 *   <li>{@code normalizeTab}, {@code tableFragment}, {@code columnas},
 *       {@code columna}, {@code normalizarSeccion}, {@code prevalidateXml},
 *       {@code consecutivoDe(Element)}, {@code normalizarBucket},
 *       {@code contiene}, {@code parseDecimal}, {@code filaMap},
 *       {@code countEstado}/{@code countPendientes} (int vs long) y demás:
 *       cuerpos por archivo o con diferencias reales de comportamiento.</li>
 *   <li>Los pares idénticos pero de bajo fan-out ({@code escape},
 *       {@code formatFecha}, {@code trimToEmpty}, {@code parseDecimalOrNull},
 *       {@code totalPages}, {@code nombreMes}, {@code paginate},
 *       {@code etiquetaEstado}, {@code etiquetaTipo}, {@code scoreSeverity},
 *       {@code barColor}, {@code barWidth}, {@code toBackupStatusDTO},
 *       {@code correosReceptor}) quedan para un paso posterior.</li>
 * </ul>
 */
public final class AppRecursoBase {

    private AppRecursoBase() {
        // Clase de utilidad: no se instancia.
    }

    /** Render HTML 200 desde una instancia Qute, con UTF-8 explícito. */
    public static Response htmlOk(@Nonnull TemplateInstance template) {
        return Response.ok(template.render())
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /** Render HTML 200 desde un fragmento ya renderizado, con UTF-8 explícito. */
    public static Response htmlOk(@Nonnull String html) {
        return Response.ok(html)
                .type(MediaType.TEXT_HTML_TYPE.withCharset("UTF-8")).build();
    }

    /** Redirección del cliente: HTMX navega y la página se vuelve a renderizar. */
    public static Response hxRedirect(@Nonnull String url) {
        return Response.status(Response.Status.OK)
                .header("HX-Redirect", url)
                .build();
    }

    /** Columna del modelo de tabla; el orden de inserción define el orden visible. */
    public static Map<String, Object> col(@Nonnull String label, @Nullable String key) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("label", label);
        map.put("key", key);
        return map;
    }

    /** Ventana de páginas del paginador: {@code page ± 2}, recortada a los bordes. */
    public static List<Integer> pageWindow(int page, int totalPages) {
        if (totalPages <= 1) {
            return List.of(1);
        }
        List<Integer> pages = new ArrayList<>();
        int from = Math.max(1, page - 2);
        int to = Math.min(totalPages, page + 2);
        for (int i = from; i <= to; i++) {
            pages.add(i);
        }
        return pages;
    }

    /** Lista vacía inmutable cuando el repositorio devuelve {@code null}. */
    public static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /** {@code null} para entrada nula o en blanco; en otro caso la entrada sin recortar. */
    @Nullable
    public static String emptyToNull(@Nullable String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    /** Búsqueda sin distinguir mayúsculas; el needle se asume ya en minúsculas. */
    public static boolean contains(@Nullable String value, @Nonnull String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Entero tolerante: {@code null} si viene nulo, en blanco o no numérico. */
    public static Integer parseIntOrNull(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
