package Controllers.Api.App;

import io.quarkus.qute.Location;
import io.quarkus.qute.Template;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W1 design-system foundation: renders every primitive introduced by the
 * foundation wave straight through Qute, with no HTTP hop and no scaffolding
 * endpoint.
 *
 * <p><b>Why render at all.</b> The eight new {@code templates/_kit/} fragments
 * shipped purely additive, so no pre-existing test could reach them: nothing
 * includes them until W5 migrates the pages. A Qute parse or expression error
 * would therefore stay invisible until the migration wave. This class closes
 * that gap by resolving each fragment through {@link io.quarkus.qute.Location}
 * and asserting the contract markers, following the
 * {@code SettingsPageTest} convention of rendering templates directly instead
 * of over HTTP.</p>
 *
 * <p><b>What it pins.</b> For each primitive: that it parses, that its required
 * parameters are genuinely required, and that it emits the documented
 * accessibility affordances. The icon/Chart.js layer is JavaScript and is
 * verified out-of-band (esbuild bundle + a direct {@code node} import of
 * {@code web/icons.js}), not here.</p>
 *
 * <p>Safe expressions ({@code foo??}) are exercised by omitting every optional
 * parameter, which is the case most likely to break.</p>
 */
@QuarkusTest
@Tag("design-system")
class DesignSystemPrimitivesTest {

    // ── _kit/icon ────────────────────────────────────────────────────────

    @Location("_kit/icon")
    Template icon;

    @Test
    @DisplayName("icon: renders a hydratable marker, decorative by default")
    void iconRendersMarkerAndIsDecorativeWithoutLabel() {
        String html = icon.data("name", "edit").render();

        assertTrue(html.contains("class=\"mi-icon\""), "must carry the .mi-icon class");
        assertTrue(html.contains("data-icon=\"edit\""), "must carry data-icon for hydration");
        assertTrue(html.contains("aria-hidden=\"true\""),
                "without a label the icon is decorative and must be aria-hidden");
        assertFalse(html.contains("<svg"),
                "the fragment must not inline path data; web/icons.js is the source of truth");
    }

    @Test
    @DisplayName("icon: a label makes the icon exposed with an accessible name")
    void iconExposesAccessibleNameWhenLabeled() {
        String html = icon.data("name", "gauge").data("label", "Dashboard").render();

        assertTrue(html.contains("role=\"img\""), "a labeled icon must be role=img");
        assertTrue(html.contains("aria-label=\"Dashboard\""), "the label becomes aria-label");
        assertFalse(html.contains("aria-hidden=\"true\""),
                "a labeled icon must not also be aria-hidden");
    }

    @Test
    @DisplayName("icon: size modifier is applied when supplied")
    void iconAppliesSizeModifier() {
        String html = icon.data("name", "plus").data("size", "large").render();

        assertTrue(html.contains("is-large"), "size must reach the class list");
    }

    // ── _kit/page-header ─────────────────────────────────────────────────

    @Location("_kit/page-header")
    Template pageHeader;

    @Test
    @DisplayName("page-header: renders exactly one h1 with title and subtitle")
    void pageHeaderRendersSingleH1() {
        String html = pageHeader
                .data("title", "Artículos")
                .data("eyebrow", "Inventario")
                .data("subtitle", "Administre los artículos del inventario")
                .render();

        assertTrue(html.contains("<h1 class=\"title is-3 page-title\">Artículos</h1>"),
                "title must render as the page's single h1");
        assertTrue(html.contains("page-eyebrow"), "eyebrow must render when supplied");
        assertTrue(html.contains("page-subtitle"), "subtitle must render when supplied");
        assertTrue(html.contains("<header class=\"page-header\">"), "root must be <header class=page-header>");
        assertFalse(html.contains("articulos-header"),
                "the module gradient banners must not come back through this component");
    }

    @Test
    @DisplayName("page-header: accent variant maps to a Bulma semantic colour")
    void pageHeaderAccentUsesSemanticColour() {
        String html = pageHeader.data("title", "POS").data("accent", "info").render();

        assertTrue(html.contains("page-header-accent is-info"),
                "accent must emit page-header-accent plus the Bulma colour modifier");
        assertTrue(html.contains("page-header-actions"), "the actions slot container must always render");
    }

    // ── _kit/metric ───────────────────────────────────────────────────────

    @Location("_kit/metric")
    Template metric;

    @Test
    @DisplayName("metric: no interactivity renders a passive div")
    void metricRendersPassiveCardByDefault() {
        String html = metric.data("label", "Activos").data("value", 1284).render();

        assertTrue(html.contains("<div class=\"metric-card\">"), "default element is a div");
        assertTrue(html.contains("<span class=\"metric-label\">Activos</span>"), "label renders first");
        assertTrue(html.contains("<span class=\"metric-value\">1284</span>"), "value renders second");
        assertFalse(html.contains("metric-detail"), "detail is omitted when not supplied");
        assertFalse(html.contains("title is-3"),
                ".metric-value must not import Bulma .title sizing (docs/ui-kit.md 10)");
    }

    @Test
    @DisplayName("metric: interactive renders a button, href renders an anchor")
    void metricSelectsElementByParameter() {
        String boton = metric.data("label", "Pendientes").data("value", 8)
                .data("interactive", true).data("active", true).render();
        assertTrue(boton.contains("<button type=\"button\" class=\"metric-card is-interactive is-active\">"),
                "interactive + active must render a button carrying both modifiers");

        String enlace = metric.data("label", "Activos").data("value", 1284)
                .data("href", "?tab=activos").render();
        assertTrue(enlace.contains("<a class=\"metric-card is-interactive\""), "href must render an anchor");
        assertTrue(enlace.contains("href=\"?tab=activos\""), "the href must be emitted verbatim");
    }

    @Test
    @DisplayName("metric: detail renders as the third line")
    void metricRendersDetail() {
        String html = metric.data("label", "Ventas Hoy").data("value", "₡1.250.000")
                .data("detail", "+8 esta semana").render();

        assertTrue(html.contains("<span class=\"metric-detail\">+8 esta semana</span>"),
                "detail must render as .metric-detail");
    }

    // ── _kit/view-tabs ───────────────────────────────────────────────────

    @Location("_kit/view-tabs")
    Template viewTabs;

    private static final List<Map<String, Object>> VISTAS = List.of(
            Map.of("key", "activos", "label", "Activos", "href", "?tab=activos",
                    "count", 1284, "active", true),
            Map.of("key", "inactivos", "label", "Inactivos", "href", "?tab=inactivos",
                    "count", 23, "active", false));

    @Test
    @DisplayName("view-tabs: data mode renders nav + anchors with aria-current on the active view")
    void viewTabsRenderServerDrivenAnchors() {
        String html = viewTabs.data("ariaLabel", "Vistas de artículos").data("tabs", VISTAS).render();

        assertTrue(html.contains("<nav class=\"view-tabs\""), "root must be a labelled nav landmark");
        assertTrue(html.contains("aria-label=\"Vistas de artículos\""), "the nav must be named");
        assertTrue(html.contains("href=\"?tab=activos\""), "each view is a real link");
        assertTrue(html.contains("aria-current=\"page\""), "the active view must be marked for AT");
        assertTrue(html.contains("view-tab-count\">1284"), "counts render in a pill");
        assertFalse(html.contains("role=\"tab\""),
                "these are navigation, not an ARIA tab widget (docs/ui-kit.md 10)");
    }

    @Test
    @DisplayName("view-tabs: slot mode renders caller markup inside the nav")
    void viewTabsRenderSlotMode() {
        String html = viewTabs.data("ariaLabel", "Vistas de inventario").render();

        assertTrue(html.contains("<nav class=\"view-tabs\""), "the nav still renders without data");
        assertTrue(html.contains("aria-label=\"Vistas de inventario\""), "ariaLabel is required and honoured");
    }

    // ── _kit/surface ─────────────────────────────────────────────────────

    @Location("_kit/surface")
    Template surface;

    @Test
    @DisplayName("surface: header, body and footer slots all render")
    void surfaceRendersAllSlots() {
        String html = surface.data("title", "Pre-validación").data("subtitle", "Última ejecución")
                .data("flush", true).data("id", "prevalidacion-panel").render();

        assertTrue(html.contains("<section class=\"surface\""), "root must be .surface");
        assertTrue(html.contains("id=\"prevalidacion-panel\""), "id must land on the section");
        assertTrue(html.contains("surface-header"), "header renders when a title is supplied");
        assertTrue(html.contains("<h2 class=\"surface-title\">Pre-validación</h2>"), "title is an h2");
        assertTrue(html.contains("surface-body is-flush"), "flush must strip body padding");
        assertTrue(html.contains("surface-subtitle\">Última ejecución"),
                "a supplied subtitle renders under the title");
        assertFalse(html.contains("surface-footer"),
                "no footer slot content means no footer strip is emitted");
    }

    @Test
    @DisplayName("surface: without a title the header is omitted")
    void surfaceOmitsHeaderWithoutTitle() {
        String html = surface.render();

        assertTrue(html.contains("<section class=\"surface\">"), "root still renders");
        assertFalse(html.contains("surface-header"), "no title means no header block");
        assertFalse(html.contains("surface-subtitle"), "no subtitle means no subtitle element");
        assertTrue(html.contains("surface-body"), "the body always renders");
    }

    // ── _kit/filter-bar ──────────────────────────────────────────────────

    @Location("_kit/filter-bar")
    Template filterBar;

    @Test
    @DisplayName("filter-bar: grid and actions regions render, method defaults to get")
    void filterBarRendersGridAndActions() {
        String html = filterBar.data("id", "filtros-recibos").render();

        assertTrue(html.contains("<section class=\"filter-bar\""), "root must be .filter-bar");
        assertTrue(html.contains("id=\"filtros-recibos\""), "id must land on the section");
        assertTrue(html.contains("class=\"filter-grid\""), "the grid region always renders");
        assertTrue(html.contains("class=\"filter-actions\""), "the actions region always renders");
        assertTrue(html.contains("method=\"get\""), "method must default to get");
    }

    // ── _kit/action-bar ──────────────────────────────────────────────────

    @Location("_kit/action-bar")
    Template actionBar;

    @Test
    @DisplayName("action-bar: count and label render, empty by default")
    void actionBarRendersCount() {
        String html = actionBar.data("count", 152).data("countLabel", "Artículos").render();

        assertTrue(html.contains("<section class=\"action-bar\""), "root must be .action-bar");
        assertTrue(html.contains("<strong>152</strong> Artículos"),
                "count and label must render in the count region");
        assertTrue(html.contains("class=\"action-buttons\""), "the buttons region always renders");
    }

    @Test
    @DisplayName("action-bar: with no data it still renders its regions")
    void actionBarRendersWithoutData() {
        String html = actionBar.render();

        assertTrue(html.contains("action-count"), "count region renders even when empty");
        assertTrue(html.contains("action-buttons"), "buttons region renders even when empty");
    }

    // ── _kit/chart-card ──────────────────────────────────────────────────

    @Location("_kit/chart-card")
    Template chartCard;

    @Test
    @DisplayName("chart-card: renders an accessible canvas when given a canvasId")
    void chartCardRendersAccessibleCanvas() {
        String html = chartCard.data("title", "Ventas por hora")
                .data("subtitle", "Últimos 30 días")
                .data("canvasId", "grafica-ventas-hora").render();

        assertTrue(html.contains("<figure class=\"chart-card\""), "root must be .chart-card");
        assertTrue(html.contains("chart-title\">Ventas por hora"), "the title is the figcaption");
        assertTrue(html.contains("id=\"grafica-ventas-hora\""), "the canvas id must be emitted");
        assertTrue(html.contains("role=\"img\""), "a canvas is opaque, so it needs a role");
        assertTrue(html.contains("aria-label=\"Ventas por hora\""),
                "the canvas must carry the chart title as its accessible name");
        assertTrue(html.contains("Su navegador no soporta canvas."),
                "the canvas needs a text fallback for AT and old browsers");
    }

    @Test
    @DisplayName("chart-card: size and height map onto the height token")
    void chartCardAppliesHeightToken() {
        String porDefecto = chartCard.data("title", "Tendencia").render();
        assertFalse(porDefecto.contains("--chart-height"),
                "the default height comes from the size token, not an inline style");

        String alto = chartCard.data("title", "Tendencia").data("size", "tall").render();
        assertTrue(alto.contains("chart-card is-tall"), "size must reach the class list");

        String explicito = chartCard.data("title", "Tendencia").data("height", "360px").render();
        assertTrue(explicito.contains("--chart-height: 360px"), "height must map onto the CSS token");
    }

    // ── _kit/toast-container (W1 extraction) ─────────────────────────────

    @Location("_kit/toast-container")
    Template toastContainer;

    @Test
    @DisplayName("toast-container: renders the mount point with its live region")
    void toastContainerRendersLiveRegion() {
        String html = toastContainer.render();

        assertTrue(html.contains("id=\"toast-container\""), "the mount point id is the OOB swap target");
        assertTrue(html.contains("aria-live=\"polite\""), "appended toasts must be announced");
        assertTrue(html.contains("aria-atomic=\"false\""), "atomic=false so only new toasts are read");
        assertTrue(html.contains("z-index: 1000"), "positioning must survive the extraction verbatim");
    }

    @Test
    @DisplayName("toast-container: renders seeded toasts, and stays empty without the data model")
    void toastContainerHonoursToastsModel() {
        String conToasts = toastContainer
                .data("toasts", List.of(Map.of("severity", "success", "message", "Listo")))
                .render();
        assertTrue(conToasts.contains("Listo"), "supplied toasts must render");
        assertTrue(conToasts.contains("is-success"), "severity must map to a Bulma colour class");

        String sinToasts = toastContainer.render();
        assertFalse(sinToasts.contains("Listo"), "no toasts model means no notifications");
        assertFalse(sinToasts.contains("class=\"notification"),
                "an absent toasts model must not render an empty notification");
    }
}
