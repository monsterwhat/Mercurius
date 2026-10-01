package Controllers;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

import io.quarkus.vertx.web.Route;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Serves the web-bundler output ({@code /static/bundle/app-<hash>.js|css})
 * straight from the application classpath, for anonymous and authenticated
 * callers alike.
 *
 * <p><b>Why this route exists (the production-only 404 it fixes).</b> The
 * pages render their assets with the bundler's Qute tag
 * ({@code {#bundle /}} in {@code templates/layout.html} and
 * {@code templates/login.html}), which emits
 * {@code /Mercurius/static/bundle/app-<hash>.js}. Those bytes ARE in the
 * runner jar, at {@code META-INF/resources/static/bundle/}. They are still
 * never served, and the login page therefore loads unstyled and without JS.
 *
 * <p>The cause is an interaction between two build steps, not a security
 * policy (the subtree is already permitted):
 * <ol>
 *   <li>The web bundler hands its output to Quarkus as a
 *       <em>generated</em> static resource
 *       ({@code io.quarkus.vertx.http.deployment.spi.GeneratedStaticResourceBuildItem}),
 *       produced while the build is already running. It is written into the
 *       application archive, which is why the files are physically present in
 *       the jar.</li>
 *   <li>What makes such a file reachable is decided per launch mode. In
 *       dev/test Quarkus installs a catch-all route for generated static
 *       resources ({@code GeneratedStaticResourcesProcessor.process}, marked
 *       {@code onlyIfNot = IsProduction}) — that is why the assets load fine
 *       under {@code @QuarkusTest} and in dev mode. In <b>production</b> that
 *       route is deliberately NOT installed; instead Quarkus expects the
 *       path to be in the static-resource index.</li>
 *   <li>This application is packaged with a servlet container (Undertow), so
 *       that index is {@code KnownPathsBuildItem}, built by
 *       {@code UndertowStaticResourcesBuildStep} from what it can see on the
 *       classpath at build time plus a
 *       {@code List<GeneratedWebResourceBuildItem>} — the
 *       <em>quarkus.deployment</em> type, which the web bundler does not
 *       produce. And {@code StaticResourcesProcessor}, the one consumer that
 *       would have added the generated endpoints to the index, returns early
 *       when the SERVLET capability is present.</li>
 * </ol>
 * So in the prod runner jar the bundle files sit in the archive with no
 * handler and no index entry: {@code GET /Mercurius/static/bundle/app-*.js}
 * falls through to a 404 (the browser then reports a MIME/script error), and
 * the deployed app is what proves it — the test profile cannot, because there
 * the generated-resource route answers.
 *
 * <p>This route closes that gap using the same file, so the served bytes are
 * identical in every launch mode. It is deliberately narrow:
 * <ul>
 *   <li>only the {@code static/bundle/} subtree — the one subtree that is
 *       generated after the index is computed; {@code /resources/**} is a
 *       real classpath resource and is already served by the index, which is
 *       why {@code /resources/css/base-theme.css} returns 200 while the bundle
 *       did not;</li>
 *   <li>single path segment, no {@code ..}, no separators, so it cannot be
 *       used to read arbitrary classpath resources;</li>
 *   <li>a name that is not on the classpath falls through to
 *       {@code next()}: a missing asset 404s exactly as before, and is never
 *       answered with a redirect to the login page.</li>
 * </ul>
 *
 * <p>{@code X-Mercurius-Static-Asset: web-bundler} marks responses produced
 * here. It exists so the contract test can tell this handler apart from
 * Quarkus' own generated-static-resource route: in the test profile that route
 * also answers 200, so a status/content-type assertion alone would stay green
 * with this route deleted, i.e. it could not pin the production fix.
 *
 * <p>Public in the permission policy
 * ({@code quarkus.http.auth.permission.public.paths} lists
 * {@code /static/bundle/*} and {@code /Mercurius/static/bundle/*}), so
 * anonymous callers reach it without a form-auth challenge. The route path is
 * relative to {@code quarkus.http.root-path=/Mercurius}, so it answers the
 * prefixed path the templates emit.
 */
@ApplicationScoped
public class BundleAssetRoute {

    /** Classpath root the bundler's generated resources are written under. */
    private static final String BUNDLE_CLASSPATH_ROOT = "META-INF/resources/static/bundle/";

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "js", "text/javascript; charset=utf-8",
            "mjs", "text/javascript; charset=utf-8",
            "css", "text/css; charset=utf-8",
            "map", "application/json; charset=utf-8",
            "json", "application/json; charset=utf-8",
            "svg", "image/svg+xml",
            "png", "image/png",
            "ico", "image/x-icon",
            "woff", "font/woff",
            "woff2", "font/woff2");

    @Route(path = "/static/bundle/*", methods = { Route.HttpMethod.GET, Route.HttpMethod.HEAD })
    void bundleAsset(RoutingContext rc) {
        String name = singleSegment(rc);
        if (name == null) {
            // Directory hit, traversal attempt or empty name: not an asset.
            rc.next();
            return;
        }
        byte[] bytes = read(name);
        if (bytes == null) {
            // Unknown asset: keep the ordinary not-found contract (404), never
            // an HTML login redirect under a script/stylesheet URL.
            rc.next();
            return;
        }
        rc.response()
                .setStatusCode(200)
                .putHeader("Content-Type", contentType(name))
                .putHeader("X-Mercurius-Static-Asset", "web-bundler")
                .end(Buffer.buffer(bytes));
    }

    /**
     * The requested file name, or {@code null} when the request is not a plain
     * single-segment asset request.
     */
    private static String singleSegment(RoutingContext rc) {
        String path = rc.normalizedPath();
        if (path == null) {
            return null;
        }
        int start = path.lastIndexOf('/');
        String name = start < 0 ? path : path.substring(start + 1);
        if (name.isEmpty() || name.contains("..") || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            return null;
        }
        return name;
    }

    private static byte[] read(String name) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) {
            loader = BundleAssetRoute.class.getClassLoader();
        }
        try (InputStream in = loader.getResourceAsStream(BUNDLE_CLASSPATH_ROOT + name)) {
            if (in == null) {
                return null;
            }
            return in.readAllBytes();
        } catch (IOException e) {
            // Unreadable classpath entry: let the regular handler answer.
            return null;
        }
    }

    private static String contentType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            String type = CONTENT_TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
            if (type != null) {
                return type;
            }
        }
        return "application/octet-stream";
    }
}
