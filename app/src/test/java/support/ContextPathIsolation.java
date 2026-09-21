package support;

import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for HTTP tests that spell request paths WITH the context prefix
 * ({@code /Mercurius/...}) verbatim.
 *
 * <p>Quarkus' test framework already points RestAssured's static
 * {@code basePath} at {@code quarkus.http.root-path}, so a request to
 * {@code "/Mercurius/login"} would otherwise be doubled to
 * {@code /Mercurius/Mercurius/login}. This base neutralizes the framework
 * basePath for the duration of each test and restores whatever was set
 * afterwards, so classes that rely on relative paths are unaffected regardless
 * of execution order.</p>
 */
public abstract class ContextPathIsolation {

    private String previousBasePath;

    @BeforeEach
    final void isolateContextPrefix() {
        previousBasePath = RestAssured.basePath;
        RestAssured.basePath = "";
    }

    @AfterEach
    final void restoreContextPrefix() {
        RestAssured.basePath = previousBasePath;
    }
}
