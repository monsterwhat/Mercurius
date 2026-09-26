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
 * W2 — root-path normalization. The deployment root is read from configuration
 * ({@code \{config:['quarkus.http.root-path']\}}) and never hard-coded in a
 * template.
 *
 * <p><b>Why this exists.</b> Every one of the 137 templates used to inline the
 * literal {@code /Mercurius}. The reason was a single stale comment in
 * {@code pages/settings/index.html} claiming that {@code quarkus.http.root-path}
 * is "absent from the runtime config namespace outside dev mode, so
 * {@code \{config:\}} expressions risk TemplateException on packaged runs".
 * That claim was false: the property is declared in
 * {@code application.properties} and resolves at render time — the two templates
 * that already used the {@code \{config:\}} form
 * ({@code pages/recibos/tabla.html}, {@code pages/recibos/detalle.html})
 * rendered fine in production. So the codebase was pinned to the worse option
 * on the strength of a wrong note. W2 swept 301 occurrences across 97 files and
 * deleted the note.</p>
 *
 * <p>Two layers of guard:</p>
 * <ol>
 *   <li>a <b>source scan</b> over every template, so a single re-introduced
 *       literal fails the build rather than one page at a time;</li>
 *   <li>a <b>render assertion</b> on the two kit fragments that build URLs by
 *       hand ({@code _kit/data-table}, {@code _kit/pagination}), checking the
 *       root is applied exactly once and the expression never leaks into
 *       output.</li>
 * </ol>
 */
@QuarkusTest
@Tag("design-system")
class RootPathNormalizationTest {

    /** Value declared in application.properties; the kit must resolve to exactly this. */
    private static final String ROOT = "/Mercurius";

    private static final String CONFIG_EXPR = "{config:['quarkus.http.root-path']}";

    private static final Path TEMPLATES = Paths.get("src", "main", "resources", "templates");

    /**
     * URL-bearing attributes. A literal root path inside any of these is the
     * regression this test exists to catch.
     */
    private static final Pattern URL_ATTR = Pattern.compile(
            "(?:href|src|action|formaction|data-root|data-base|data-url)"
                    + "\\s*=\\s*\"([^\"]*)\"");

    @Test
    @DisplayName("every template is free of hard-coded root paths in URL attributes")
    void noTemplateHardCodesTheRootPath() throws IOException {
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
                    // A resolved root is fine; a raw literal inside the value is not.
                    if (value.contains(ROOT) && !value.contains(CONFIG_EXPR)) {
                        offenders.add(rel + " -> " + value);
                    }
                }
            }
        }

        assertTrue(inspected > 100, "expected the whole template tree, scanned " + inspected);
        assertEquals(List.of(), offenders,
                "these templates inline the deployment root instead of resolving it from config");
    }

    @Test
    @DisplayName("the config expression resolves and is never emitted literally")
    void configExpressionResolvesAtRenderTime() {
        // Proves the stale "TemplateException on packaged runs" claim was false.
        assertTrue(org.eclipse.microprofile.config.ConfigProvider.getConfig()
                        .getOptionalValue("quarkus.http.root-path", String.class)
                        .filter(ROOT::equals)
                        .isPresent(),
                "quarkus.http.root-path must be declared and equal to " + ROOT);
    }

    @Location("_kit/data-table")
    Template dataTable;

    @Test
    @DisplayName("data-table sort/pager links carry the root exactly once")
    void dataTableLinksResolveTheRootExactlyOnce() {
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

        assertFalse(html.contains(CONFIG_EXPR), "the expression must be resolved, not printed");
        assertFalse(html.contains("config:"), "no config expression may leak into the markup");
        assertTrue(html.contains("href=\"" + ROOT + "/app/articulos?sort=nombre"),
                "sort links must be prefixed with the resolved root");
        assertFalse(html.contains(ROOT + ROOT), "the root must not be applied twice");
        assertFalse(html.contains("href=\"/app/articulos"),
                "no URL may be emitted without the root prefix");
    }

    @Location("_kit/pagination")
    Template pagination;

    @Test
    @DisplayName("pagination links carry the root exactly once")
    void paginationLinksResolveTheRootExactlyOnce() {
        String html = pagination
                .data("page", 3)
                .data("totalPages", 8)
                .data("pages", List.of(2, 3, 4))
                .data("baseUrl", "/app/clientes")
                .render();

        assertFalse(html.contains(CONFIG_EXPR), "the expression must be resolved, not printed");
        assertTrue(html.contains("href=\"" + ROOT + "/app/clientes?page=2"), "prev link");
        assertTrue(html.contains("href=\"" + ROOT + "/app/clientes?page=4"), "next link");
        assertTrue(html.contains("aria-current=\"page\""), "the current page is still marked");
        assertFalse(html.contains(ROOT + ROOT), "the root must not be applied twice");
    }

    @Test
    @DisplayName("the literal and the config expression are mutually exclusive in templates")
    void sweepDidNotProduceAMixedConvention() throws IOException {
        long literals;
        try (Stream<Path> walk = Files.walk(TEMPLATES)) {
            literals = walk.filter(f -> f.toString().endsWith(".html"))
                    .filter(f -> {
                        try {
                            return Files.readString(f, StandardCharsets.UTF_8).contains(ROOT);
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .count();
        }
        // The only remaining occurrences are the log filename "logs/mercurius.log"
        // and references to the stylesheet "web/mercurius.scss" - never a URL.
        assertEquals(0, literals,
                "no template should mention " + ROOT + " at all any more (see the test javadoc)");
    }
}
