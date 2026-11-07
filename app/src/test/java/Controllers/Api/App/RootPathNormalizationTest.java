package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Root-path normalization after the app moved to the server root.
 *
 * <p><b>Why this exists.</b> The application used to be mounted at
 * {@code quarkus.http.root-path=/Mercurius} and every template prefixed its
 * URLs with {@code \{config:['quarkus.http.root-path']\}}. At the server root
 * that expression resolves to {@code "/"}, so keeping it would emit
 * {@code //app/...} — a protocol-relative URL whose "host" is {@code app}.
 * The prefix expression was therefore removed from the templates.</p>
 *
 * <p>Two layers of guard:</p>
 * <ol>
 *   <li>a <b>source scan</b> over every template, so a re-introduced prefix or a
 *       double slash fails the build rather than one page at a time;</li>
 *   <li>a <b>render assertion</b> on the two kit fragments that build URLs by
 *       hand ({@code _kit/data-table}, {@code _kit/pagination}), checking the
 *       links are absolute and single-slashed.</li>
 * </ol>
 */
@QuarkusTest
@Tag("design-system")
class RootPathNormalizationTest {

    /** Value declared in application.properties. */
    private static final String ROOT = "/";

    /** Removed prefix: any occurrence in a URL is a regression. */
    private static final String STALE_PREFIX = "/Mercurius";

    /** Removed config expression: must not come back (it yields "//app"). */
    private static final String REMOVED_EXPR = "{config:['quarkus.http.root-path']}";

    private static final Path TEMPLATES = Paths.get("src", "main", "resources", "templates");

    /**
     * URL-bearing attributes. Values are checked for a leading "//" (a
     * protocol-relative URL) and for the removed deployment prefix.
     */
    private static final Pattern URL_ATTR = Pattern.compile(
            "(?:href|src|action|formaction|data-root|data-base|data-url|hx-get|hx-post|hx-put|hx-delete|hx-patch)"
                    + "\\s*=\\s*\"([^\"]*)\"");

    @Test
    @DisplayName("every template emits absolute, single-slashed URLs")
    void noTemplateEmitsADoubleSlashOrTheRemovedPrefix() throws IOException {
        assertTrue(Files.isDirectory(TEMPLATES),
                "templates not found at " + TEMPLATES.toAbsolutePath()
                        + " - run the suite from the app/ module");

        List<String> offenders = new ArrayList<>();
        int inspected = 0;

        try (Stream<Path> walk = Files.walk(TEMPLATES)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".html"))::iterator) {
                String rel = TEMPLATES.relativize(p).toString();
                String src = Files.readString(p, StandardCharsets.UTF_8);
                inspected++;

                Matcher m = URL_ATTR.matcher(src);
                while (m.find()) {
                    String value = m.group(1);
                    if (value.startsWith("//")) {
                        offenders.add(rel + " -> protocol-relative " + value);
                    }
                    if (value.contains(STALE_PREFIX)) {
                        offenders.add(rel + " -> stale prefix " + value);
                    }
                }
            }
        }

        assertTrue(inspected > 100, "expected the whole template tree, scanned " + inspected);
        assertEquals(List.of(), offenders,
                "these templates emit a double slash or the removed deployment prefix");
    }

    @Test
    @DisplayName("the removed config expression never comes back")
    void removedPrefixExpressionIsGone() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(TEMPLATES)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".html"))::iterator) {
                if (Files.readString(p, StandardCharsets.UTF_8).contains(REMOVED_EXPR)) {
                    offenders.add(TEMPLATES.relativize(p).toString());
                }
            }
        }
        assertEquals(List.of(), offenders,
                "the root-path prefix expression must stay removed while the app is served at \"/\"");
    }

    @Test
    @DisplayName("the app is configured at the server root")
    void rootPathIsTheServerRoot() {
        assertTrue(org.eclipse.microprofile.config.ConfigProvider.getConfig()
                        .getOptionalValue("quarkus.http.root-path", String.class)
                        .filter(ROOT::equals)
                        .isPresent(),
                "quarkus.http.root-path must be " + ROOT);
    }

    @Location("_kit/data-table")
    Template dataTable;

    @Test
    @DisplayName("data-table sort/pager links are absolute and single-slashed")
    void dataTableLinksAreAbsolute() {
        String html = dataTable
                .data("id", "t")
                .data("baseUrl", "/app/articulos")
                .data("headers", List.of(
                        java.util.Map.of("label", "Nombre", "key", "nombre"),
                        java.util.Map.of("label", "Stock")))
                .data("sortKey", "nombre")
                .data("sortDir", "asc")
                .data("page", 2)
                .data("size", 20)
                .data("total", 152)
                .data("totalPages", 8)
                .data("pages", List.of(1, 2, 3))
                .render();

        assertFalse(html.contains(REMOVED_EXPR), "the expression must stay removed");
        assertFalse(html.contains("//app/"), "URLs must not start with a double slash");
        assertTrue(html.contains("href=\"/app/articulos?sort=nombre"),
                "sort links must be absolute root paths");
    }

    @Location("_kit/pagination")
    Template pagination;

    @Test
    @DisplayName("pagination links are absolute and single-slashed")
    void paginationLinksAreAbsolute() {
        String html = pagination
                .data("page", 3)
                .data("totalPages", 8)
                .data("pages", List.of(2, 3, 4))
                .data("baseUrl", "/app/clientes")
                .render();

        assertFalse(html.contains(REMOVED_EXPR), "the expression must stay removed");
        assertFalse(html.contains("//app/"), "URLs must not start with a double slash");
        assertTrue(html.contains("href=\"/app/clientes?page=2"), "prev link");
        assertTrue(html.contains("href=\"/app/clientes?page=4"), "next link");
        assertTrue(html.contains("aria-current=\"page\""), "the current page is still marked");
    }
}
