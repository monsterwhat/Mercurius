package support;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;

/**
 * Base class for HTTP tests: aligns RestAssured with the application's
 * build-time root path ({@code /Mercurius}). Extend this in any test that
 * drives HTTP endpoints with relative paths.
 */
public abstract class AppBase {

    @BeforeEach
    void alignRestAssuredBasePath() {
        RestAssured.basePath = "/Mercurius";
    }
}
