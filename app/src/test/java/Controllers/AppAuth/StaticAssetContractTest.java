package Controllers.AppAuth;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CONTRACT: an ANONYMOUS browser must be able to load the login page's own
 * assets. The login page is pre-auth by definition, so if its JS/CSS 303 away
 * the page renders unstyled and the browser reports MIME errors.
 *
 * <p><b>Why this test exists (the regression it pins).</b> A previous fix
 * (a1b8eef) enumerated nested static subtrees into
 * {@code quarkus.http.auth.permission.public.paths} because anonymous asset
 * GETs appeared to "303 to /login". That diagnosis was wrong: those 303s were
 * never a form-auth challenge. They came from
 * {@link Controllers.FallbackResource}, a JAX-RS catch-all declared
 * {@code @Path("/{remaining:.+}")}, which matches EVERY remaining path —
 * including {@code /static/bundle/app-<hash>.js}. Because a JAX-RS resource
 * match pre-empts Vert.x static-resource serving, the catch-all answered
 * before the bundle could ever be written to the response, and its
 * anonymous branch returned {@code seeOther("/login")}.
 *
 * <p>So the security config was (and still is) correct — the assets were
 * permitted all along. Adding more permit patterns could never have fixed it,
 * which is why the symptom came back with no test pinning it. The real
 * contract is the one asserted below: <b>an anonymous GET of a real bundle
 * asset answers 200 with the asset's own content type and body</b> — a
 * redirect to an HTML login page fails every part of that.</p>
 *
 * <p>Discriminating the two failure modes matters when this test goes red:
 * <ul>
 *   <li><b>303 + {@code csrf-token} cookie</b> = the catch-all hijacked the
 *       request (regression). Location points at the login page and the body
 *       is empty, so the browser rejects it as a MIME mismatch.</li>
 *   <li><b>302 + {@code quarkus-redirect-location} cookie</b> = a genuine
 *       form-auth challenge, i.e. the security config really did deny it.</li>
 * </ul>
 * Those two are deliberately distinguished in the assertion messages.
 */
@QuarkusTest
@Tag("auth-static")
class StaticAssetContractTest extends support.ContextPathIsolation {

    private static final String BASE = "/Mercurius";

    /**
     * The bundle filenames are content-hashed by the web bundler, so they are
     * read from the build output rather than hard-coded: pinning a literal
     * hash would make this test fail on every unrelated frontend change.
     * Reads {@code target/classes/META-INF/resources/static/bundle} and
     * {@code target/web-bundler/{prod,test}/dist} — whichever exists.
     */
    private static List<String> bundleAssetNames() {
        List<String> names = new ArrayList<>();
        for (String dir : List.of("target/classes/META-INF/resources/static/bundle",
                "target/web-bundler/test/dist/static/bundle",
                "target/web-bundler/prod/dist/static/bundle")) {
            Path p = Path.of(dir);
            if (!Files.isDirectory(p)) {
                continue;
            }
            try (Stream<Path> s = Files.list(p)) {
                s.map(x -> x.getFileName().toString())
                        // source maps are diagnostics, never fetched by the page
                        .filter(n -> (n.endsWith(".js") || n.endsWith(".css")) && !n.endsWith(".map"))
                        .forEach(names::add);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot list bundle dir " + p, e);
            }
        }
        if (names.isEmpty()) {
            throw new IllegalStateException(
                    "No web-bundler output found; the bundler did not run, so this contract cannot be verified");
        }
        return names;
    }

    // ── the regression: anonymous asset GET must be SERVED, not redirected ──

    @Test
    void anonymousGetsRealBundleJsWith200AndJsContentType() {
        String name = bundleAssetNames().stream().filter(n -> n.endsWith(".js")).findFirst()
                .orElseThrow(() -> new AssertionError("web-bundler produced no .js asset"));
        String path = BASE + "/static/bundle/" + name;

        given()
                .when().get(path)
                .then()
                // A catch-all hijack answers 303 seeOther(/login) with an empty
                // body; a form-auth challenge answers 302 with a redirect-location
                // cookie. Only a served asset reaches 200.
                .statusCode(equalTo(200))
                .contentType(startsWith("text/javascript"))
                .body(not(containsString("<html")));
    }

    @Test
    void anonymousGetsRealBundleCssWith200AndCssContentType() {
        String name = bundleAssetNames().stream().filter(n -> n.endsWith(".css")).findFirst()
                .orElseThrow(() -> new AssertionError("web-bundler produced no .css asset"));
        String path = BASE + "/static/bundle/" + name;

        given()
                .when().get(path)
                .then()
                .statusCode(equalTo(200))
                .contentType(startsWith("text/css"))
                .body(not(containsString("<html")));
    }

    // ── other static subtrees the app serves ──────────────────────────────

    @Test
    void anonymousGetsFaviconIco() {
        given()
                .when().get(BASE + "/resources/favicon/favicon.ico")
                .then()
                .statusCode(equalTo(200))
                .contentType(not(startsWith("text/html")));
    }

    @Test
    void anonymousGetsLogoPng() {
        given()
                .when().get(BASE + "/resources/imgs/logo/Mercurius.png")
                .then()
                .statusCode(equalTo(200))
                .contentType(startsWith("image/"));
    }

    @Test
    void anonymousGetsPlainCssFromLegacyResourcesTree() {
        given()
                .when().get(BASE + "/resources/css/base-theme.css")
                .then()
                .statusCode(equalTo(200))
                .contentType(startsWith("text/css"));
    }

    // ── the login page itself must reference assets that actually resolve ──

    @Test
    void loginPageAssetReferencesAllResolveForAnonymousCaller() {
        String html = given()
                .when().get(BASE + "/login")
                .then().statusCode(equalTo(200))
                .extract().body().asString();

        List<String> refs = assetRefs(html);
        if (refs.isEmpty()) {
            throw new AssertionError(
                    "login page emitted no /static/bundle references; the {#bundle} tag produced nothing, "
                            + "so this test would pass vacuously");
        }
        for (String ref : refs) {
            given()
                    .when().get(ref)
                    .then()
                    .statusCode(equalTo(200));
        }
    }

    /** Pulls src/href values that point at the bundle tree. */
    private static List<String> assetRefs(String html) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:src|href)=\"([^\"]*/static/bundle/[^\"]+)\"").matcher(html);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    // ── the bundle must be served by the app, not only by a build-time route ──

    /**
     * Pins the PRODUCTION fix, and the reason it needs its own assertion.
     *
     * <p>The regression this guards is invisible to every other test in this
     * class: in the test profile Quarkus installs a catch-all route for
     * <em>generated</em> static resources, so a bundle hit answers 200 with
     * the right content type even with the application contributing nothing.
     * In production that route is deliberately absent
     * ({@code GeneratedStaticResourcesProcessor.process} is
     * {@code onlyIfNot = IsProduction}); there the bundle path has to be in the
     * static-resource index, which for this servlet-packaged app is computed
     * before the bundler's files exist. The deployed runner jar therefore
     * 404s the bundle — bytes in the jar, no handler, no index entry — and the
     * login page loads unstyled. Only a marker that the application itself
     * answered distinguishes that world from the test profile's.
     *
     * @see Controllers.BundleAssetRoute
     */
    @Test
    void bundleAssetIsServedByTheApplicationRoute() {
        String name = bundleAssetNames().stream().filter(n -> n.endsWith(".js")).findFirst()
                .orElseThrow(() -> new AssertionError("web-bundler produced no .js asset"));
        int length = given()
                .when().get(BASE + "/static/bundle/" + name)
                .then()
                .statusCode(equalTo(200))
                .contentType(startsWith("text/javascript"))
                // 200 here is not proof: Quarkus' own generated-static-resource
                // route serves the same file under the test profile, and it is
                // not installed in production. This header is the app's own
                // handler, i.e. the one that also runs in production.
                .header("X-Mercurius-Static-Asset", equalTo("web-bundler"))
                .extract().asByteArray().length;
        if (length == 0) {
            throw new AssertionError("served bundle asset was empty");
        }
    }

    /**
     * The route that serves the bundle must not widen into "serve anything on
     * the classpath": an unknown bundle name keeps the plain 404 contract, and
     * is never answered with a redirect to the login page (the MIME error that
     * made this bug look like an auth problem in the first place).
     */
    @Test
    void unknownBundleAssetStillAnswersPlain404() {
        given()
                .redirects().follow(false)
                .when().get(BASE + "/static/bundle/app-ZZZZnotarealhash.js")
                .then()
                .statusCode(equalTo(404))
                .header("Location", nullValue());
    }

    // ── the catch-all must still do its job for genuinely dead paths ───────

    @Test
    void nonexistentPathStillRedirectsAnonymousToLogin() {
        // Guards the fix: narrowing the catch-all must not delete the
        // anonymous-lands-on-login behaviour for real dead links.
        given()
                .redirects().follow(false)
                .when().get(BASE + "/definitely-not-a-real-page-zzz")
                .then()
                .statusCode(org.hamcrest.Matchers.anyOf(equalTo(303), equalTo(404)));
    }

    /**
     * The invariant that actually discriminates the regression, stated
     * independently of which handler ends up serving a given file.
     *
     * <p>Why this test and not just the "real asset" ones above: the web
     * bundler serves its own {@code dist/} output through a route that
     * outranks JAX-RS, so in the test profile a bundle hit can answer 200 even
     * while the catch-all is still broken. Asserting on a <em>real</em> bundle
     * file alone would therefore pass in CI both before and after the fix. The
     * {@code /resources/*} assertions do discriminate, but they depend on
     * those files existing in {@code META-INF/resources}.
     *
     * <p>So pin the routing rule itself: under the static subtrees the
     * catch-all must never claim the path, whether or not a file is there.
     * A missing asset 404s; it must never become a 303 to an HTML login page.
     * That 303 is precisely what made browsers log MIME errors and left the
     * login page unstyled.
     */
    @Test
    void staticSubtreesAreNeverHijackedByTheDeadLinkCatchAll() {
        for (String dead : List.of("/static/zzz-not-here.js", "/resources/zzz/not-here.css",
                "/resources/imgs/zzz-not-here.png")) {
            int status = given()
                    .redirects().follow(false)
                    .when().get(BASE + dead)
                    .then().extract().statusCode();
            if (status == 303 || status == 302) {
                throw new AssertionError(
                        "catch-all hijacked static subtree " + dead + " (answered " + status
                                + " redirect); static assets must be served by the static handler, "
                                + "and a missing one must 404 rather than redirect to the login page");
            }
        }
    }

    @Test
    void unknownApiPathStillAnswersJson404() {
        given()
                .when().get(BASE + "/api/app/definitely-not-real-zzz")
                .then()
                .statusCode(equalTo(404))
                .contentType(startsWith("application/json"));
    }

    // ── the security surface must stay closed ─────────────────────────────

    @Test
    void appSurfaceStillChallengesAnonymous() {
        // If the static fix were implemented by blanket-permitting everything,
        // this would start answering 200/303-from-a-page instead of a challenge.
        given()
                .redirects().follow(false)
                .when().get(BASE + "/app/cabys")
                .then()
                .statusCode(equalTo(302));
    }

    /** Guards against a vacuous green: the served asset must really have bytes. */
    @Test
    void servedJsIsNotEmpty() throws IOException {
        String name = bundleAssetNames().stream().filter(n -> n.endsWith(".js")).findFirst()
                .orElseThrow(() -> new AssertionError("web-bundler produced no .js asset"));
        byte[] body = given()
                .when().get(BASE + "/static/bundle/" + name)
                .then().statusCode(equalTo(200))
                .extract().asByteArray();
        if (body.length == 0) {
            throw new AssertionError("served bundle asset was empty");
        }
    }
}
