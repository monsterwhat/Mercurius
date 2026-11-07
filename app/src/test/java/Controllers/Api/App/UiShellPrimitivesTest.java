package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract tests for the UI-modernization primitives (spec §6): the detail
 * drawer and the empty / loading / form-section states.
 *
 * <p>Same rationale as {@link DesignSystemPrimitivesTest}: the fragments ship
 * before any page includes them, so a Qute parse or expression error would
 * stay invisible until the migration wave. Optional parameters are omitted on
 * purpose, because that is the case most likely to break a safe expression.</p>
 */
@QuarkusTest
@Tag("design-system")
class UiShellPrimitivesTest {

    @Location("_kit/drawer")
    Template drawer;

    @Location("_kit/empty-state")
    Template emptyState;

    @Location("_kit/loading-state")
    Template loadingState;

    @Location("_kit/form-section")
    Template formSection;

    @Test
    @DisplayName("drawer: modal semantics, a labelled title and its own body id")
    void drawerRendersDialogSemantics() {
        String html = drawer.data("id", "detalle-factura")
                .data("title", "Factura 001")
                .render();

        assertTrue(html.contains("class=\"kit-drawer-root\" id=\"detalle-factura\""),
                "the root must carry the id the opener targets");
        assertTrue(html.contains("role=\"dialog\""), "a drawer is a dialog");
        assertTrue(html.contains("aria-modal=\"true\""), "a drawer is modal");
        assertTrue(html.contains("aria-labelledby=\"detalle-factura-title\""),
                "the dialog must be labelled by its title");
        assertTrue(html.contains("id=\"detalle-factura-body\""),
                "the body id is what hx-target points at");
        assertTrue(html.contains("x-cloak"), "no flash before Alpine boots");
        assertFalse(html.contains("kit-drawer-md"), "size is optional and must not default in markup");
    }

    @Test
    @DisplayName("drawer: size modifier is applied when supplied")
    void drawerAppliesSize() {
        String html = drawer.data("id", "d").data("title", "T").data("size", "lg").render();
        assertTrue(html.contains("kit-drawer-lg"), "size must reach the class list");
    }

    @Test
    @DisplayName("empty-state: title only, no action, no icon markup")
    void emptyStateWithoutOptionals() {
        String html = emptyState.data("title", "Sin resultados").render();

        assertTrue(html.contains("empty-state-title"), "the title is the required part");
        assertTrue(html.contains("Sin resultados"), "the title text must render");
        assertFalse(html.contains("<a "), "no action unless an href and label are supplied");
        assertFalse(html.contains("empty-state-icon"), "no icon wrapper without an icon key");
    }

    @Test
    @DisplayName("empty-state: action href is root-prefixed by the kit")
    void emptyStateActionUsesTheKitRoot() {
        String html = emptyState.data("title", "Sin resultados")
                .data("actionHref", "/app/articulos")
                .data("actionLabel", "Ver artículos")
                .render();

        assertTrue(html.contains("href=\"/app/articulos\""),
                "the kit prefixes the deployment root exactly once");
        assertFalse(html.contains("/Mercurius"), "no double prefix");
    }

    @Test
    @DisplayName("loading-state: three skeleton rows and a polite announcement")
    void loadingStateRendersSkeletonRows() {
        String fallback = loadingState.render();
        assertTrue(fallback.contains("Cargando"), "default label must render");
        assertTrue(count(fallback, "loading-state-row\"") == 3,
                "three skeleton rows, got " + count(fallback, "loading-state-row\""));
        assertTrue(fallback.contains("role=\"status\"") && fallback.contains("aria-live"),
                "a loading state announces itself politely");

        String labelled = loadingState.data("label", "Cargando artículos").render();
        assertTrue(labelled.contains("Cargando artículos"), "the label must reach the markup");
    }

    @Test
    @DisplayName("form-section: two columns by default, three when asked")
    void formSectionRendersColumns() {
        String two = formSection.data("title", "Datos generales").render();
        assertTrue(two.contains("form-grid-2"), "two columns is the default");
        assertTrue(two.contains("<h2 class=\"form-section-title\">Datos generales</h2>"),
                "the section title is a real heading");

        String three = formSection.data("title", "Datos").data("columns", 3).render();
        assertTrue(three.contains("form-grid-3"), "columns must reach the class list");
    }

    private static int count(String haystack, String needle) {
        int found = 0;
        int at = haystack.indexOf(needle);
        while (at >= 0) {
            found++;
            at = haystack.indexOf(needle, at + needle.length());
        }
        return found;
    }
}
