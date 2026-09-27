package Controllers.Api.App;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The "Nueva Factura" launcher contract: the POS must open in the named
 * {@code nuevaFactura} popup, not in a tab, and no activation path may hijack
 * or silently discard the user's navigation.
 *
 * <p><b>What regressed.</b> The item used to carry
 * {@code onclick="window.open(...); return false;"}. That form is wrong in three
 * ways, all of which this class pins: an inline handler runs on EVERY
 * activation, so a Ctrl+click or a middle-click was hijacked into a popup
 * instead of the tab/window the user asked for; {@code return false} made a
 * blocked popup a dead button; and the URL was duplicated between {@code href}
 * and the handler, so the deployment root had a second place to go stale.
 * The launcher is now declarative ({@code data-kit-popup} +
 * {@code data-kit-popup-features}, read by {@code kitPopup()} in
 * {@code web/index.js}) over the same {@code href}.</p>
 *
 * <p><b>What is asserted here vs out of band.</b> Per the
 * {@code DesignSystemPrimitivesTest} convention, the behaviour of
 * {@code web/*.js} is JavaScript and is verified out of band (a direct
 * {@code node} harness over the real {@code kitPopup} body). This class pins
 * everything a template can be held to: the markup contract the handler reads,
 * the role gate around it, root-path resolution, and the absence of the inline
 * anti-pattern anywhere in the tree.</p>
 *
 * <p>Real endpoints only, no scaffolding. The fixture is chosen so the gate is
 * proven in both directions with the navbar's own gate tokens and nothing else:
 * {@code /app/recibos} is a layout page a {@code facturacion}-only identity may
 * open ({@code RecibosPagesResource}, {@code @RolesAllowed{admin,facturacion}}),
 * and {@code /app/reportes} is the equivalent for a {@code registro}-only
 * identity. Deliberately NOT the POS page: the navbar is a layout fragment, so
 * pinning it must not make this test a hostage of whatever else is changing in
 * {@code pages/facturas}.</p>
 */
@QuarkusTest
@Tag("design-system")
class NavbarPopupLauncherTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";

    /** Layout page a facturacion-only identity can open. */
    private static final String RECIBOS_URL = "/app/recibos";

    /** Layout page a registro-only identity can open. */
    private static final String REPORTES_URL = "/app/reportes";

    private static final String ROOT = "/Mercurius";

    private static final String CONFIG_EXPR = "{config:['quarkus.http.root-path']}";

    /** The window name the POS must be opened in, and reused across clicks. */
    private static final String WINDOW_NAME = "nuevaFactura";

    /** The geometry the POS has always been launched with. */
    private static final String FEATURES =
            "popup=yes,width=1280,height=800,left=120,top=80";

    private static final Path TEMPLATES = Paths.get("src", "main", "resources", "templates");

    /**
     * The anti-pattern: an inline handler that opens a window itself. Matched as
     * a whole {@code onclick} attribute, so the word "onclick" in a comment, and
     * the unrelated modal/clipboard handlers other pages still carry, cannot
     * trip it.
     */
    private static final Pattern INLINE_POPUP = Pattern.compile(
            "onclick\\s*=\\s*\"[^\"]*window\\.open\\s*\\([^\"]*\"");

    private static String html(String path) {
        Response r = given().redirects().follow(false).when().get(path);
        r.then().statusCode(200).contentType(ContentType.HTML);
        return r.asString();
    }

    // ── the markup the handler reads ────────────────────────────────────

    @Test
    @TestSecurity(user = "cajero", roles = {"facturacion"})
    @DisplayName("the launcher declares the popup name and geometry as data attributes")
    void launcherDeclaresItsWindowNameAndGeometry() {
        String body = html(BASE + RECIBOS_URL);

        assertTrue(body.contains("data-kit-popup=\"" + WINDOW_NAME + "\""),
                "the window name is the handler's only input for the popup target");
        assertTrue(body.contains("data-kit-popup-features=\"" + FEATURES + "\""),
                "the requested geometry must survive verbatim: " + FEATURES);
        assertTrue(body.contains(">Nueva Factura</a>"),
                "the launcher keeps its accessible name and visible label");
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"facturacion"})
    @DisplayName("the popup URL is the anchor's own href, root-prefixed exactly once")
    void popupUrlComesFromTheRootPrefixedHref() {
        String body = html(BASE + RECIBOS_URL);

        assertTrue(body.contains("href=\"" + ROOT + "/app/pos/standalone\""),
                "the href the handler reads must carry the resolved deployment root");
        assertFalse(body.contains(CONFIG_EXPR), "the config expression must resolve, not leak");
        assertFalse(body.contains(ROOT + ROOT), "the root must not be applied twice");
        assertFalse(body.contains("config:"), "no config expression may leak into the markup");
    }

    @Test
    @TestSecurity(user = "cajero", roles = {"facturacion"})
    @DisplayName("the launcher stays a real link: blank target, noopener, no role=button, Bulma classes kept")
    void launcherKeepsTheLinkAffordances() {
        String body = html(BASE + RECIBOS_URL);
        String launcher = anchorWith(body, "data-kit-popup");

        assertTrue(launcher != null, "expected an anchor carrying the launcher attribute");
        // target=_blank keeps the blocked-popup fallback (and the no-JS path)
        // in a new tab instead of replacing the current page. rel=noopener
        // severs window.opener for that fallback tab.
        assertTrue(launcher.contains("target=\"_blank\""),
                "target=_blank is what makes the fallback open a tab, not this page");
        assertTrue(launcher.contains("rel=\"noopener\""),
                "rel=noopener severs window.opener for the fallback tab");
        // A role=button link would be announced as a button and would lose the
        // browser's own "open in new tab"; the control genuinely is a link.
        assertFalse(launcher.contains("role=\"button\""),
                "keep it a link: href is the no-JS path and the blocked-popup fallback");
        assertTrue(launcher.contains("aria-haspopup"),
                "the separate window must be announced to assistive tech");
        assertTrue(launcher.contains("class=\"button is-dark\""),
                "navbar styling is unchanged");
    }

    // ── role gating is untouched ────────────────────────────────────────

    @Test
    @TestSecurity(user = "archivista", roles = {"registro"})
    @DisplayName("an identity without facturacion does not get the launcher")
    void launcherStaysBehindTheFacturacionRole() {
        String body = html(BASE + REPORTES_URL);

        assertTrue(body.contains("<nav class=\"navbar is-black\""),
                "the navbar itself must render, so the absence below is the gate and not a blank page");
        assertFalse(body.contains("data-kit-popup"),
                "the launcher is facturacion-gated and this identity holds only registro");
        assertFalse(body.contains("Nueva Factura"),
                "the label must not leak either");
    }

    // ── one implementation, no inline copies ────────────────────────────

    @Test
    @DisplayName("no template opens a window from an inline handler")
    void noTemplateReintroducesTheInlineLauncher() throws IOException {
        assertTrue(Files.isDirectory(TEMPLATES),
                "templates not found at " + TEMPLATES.toAbsolutePath()
                        + " - run the suite from the app/ module");

        List<String> offenders = new ArrayList<>();
        int inspected = 0;

        try (Stream<Path> walk = Files.walk(TEMPLATES)) {
            for (Path p : (Iterable<Path>) walk.filter(f -> f.toString().endsWith(".html"))::iterator) {
                String src = Files.readString(p, StandardCharsets.UTF_8);
                inspected++;
                Matcher m = INLINE_POPUP.matcher(src);
                if (m.find()) {
                    offenders.add(TEMPLATES.relativize(p) + " -> " + m.group());
                }
            }
        }

        assertTrue(inspected > 100, "expected the whole template tree, scanned " + inspected);
        assertThat(offenders).as("a popup launcher belongs in web/index.js (kitPopup) driven by "
                + "data-kit-popup, not in a per-page onclick: it cannot tell a Ctrl+click "
                + "from a plain one").isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** The opening {@code <a ...>} tag of the anchor carrying {@code marker}. */
    private static String anchorWith(String body, String marker) {
        int at = body.indexOf(marker);
        if (at < 0) {
            return null;
        }
        int start = body.lastIndexOf("<a ", at);
        int end = body.indexOf('>', at);
        return start < 0 || end < 0 ? null : body.substring(start, end);
    }
}
