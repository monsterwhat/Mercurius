package support;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for HTTP tests: aligns RestAssured with the application's
 * build-time root path. The app is served at the server root
 * ({@code quarkus.http.root-path=/}), so the base path is empty and every
 * request path starts with {@code /}.
 */
public abstract class AppBase {

    @BeforeEach
    void alignRestAssuredBasePath() {
        RestAssured.basePath = "";
    }
}
