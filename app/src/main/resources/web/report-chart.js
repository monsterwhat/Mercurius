/**
 * Shared report-chart bootstrap (P2 Phase 2.1).
 *
 * ONE payload per canvas, emitted by the server and serialized with
 * Utils/ReportChartJson (never concatenated inside a Qute template):
 *
 *     <figure class="chart-card">
 *         <div class="chart-body">
 *             <canvas id="grafica-x"
 *                     data-report-chart="datos-grafica-x"></canvas>
 *         </div>
 *     </figure>
 *     <script type="application/json" id="datos-grafica-x">
 *         {"type":"bar","labels":["..."],"series":[{"label":"Ventas","values":[1]}]}
 *     </script>
 *
 * Payload shape:
 *     type        "bar" | "line" (default "bar")
 *     label       series-level label, informational
 *     labels      category labels
 *     series      [{ label, values }], always an array (1..n)
 *     legend      boolean, whether Chart.js draws a legend
 *     emptyLabel  optional in-frame empty-state copy
 *
 * This module is the ONLY initializer: report pages carry no chart <script>.
 * It mounts every [data-report-chart] once on first load, then re-mounts after
 * an htmx swap so a partial table refresh redraws its chart instead of leaving
 * a stale bitmap behind. A fragment that swaps a canvas MUST carry its payload
 * script in the same swap; the marker is resolved with getElementById() against
 * the live document.
 *
 * Colours come from the same tokens as chart-config.js
 * (--mercurius-chart-series-1/2, resolved through MercuriusCharts.resolveColour
 * because a canvas cannot paint var()). The second series is a lighter step of
 * the same hue, so a multi-series chart stays one visual subject.
 */

const DEFAULT_EMPTY_LABEL = 'Sin datos para el período';
const RENDER_ERROR_LABEL = 'No se pudo dibujar el gráfico';
const FALLBACK_COLOUR = '#00d1b2';

/**
 * Resolve the two sanctioned series colours from the live CSS custom
 * properties, falling back to the seed colour when the tokens are missing.
 *
 * @returns {string[]} colours in series order
 */
function seriesColours() {
    const charts = window.MercuriusCharts;
    const fallback = FALLBACK_COLOUR;
    const resolve = function (token) {
        if (!charts || typeof charts.resolveColour !== 'function') {
            return fallback;
        }
        const raw = getComputedStyle(document.documentElement).getPropertyValue(token);
        return charts.resolveColour(raw, fallback);
    };
    return [resolve('--mercurius-chart-series-1'), resolve('--mercurius-chart-series-2')];
}

/**
 * Turn a payload into the flat config MercuriusCharts.create() accepts.
 *
 * @param {object} payload parsed payload
 * @returns {object} { type, labels, datasets, options, emptyLabel }
 */
function buildConfig(payload) {
    const colours = seriesColours();
    const type = payload.type === 'line' ? 'line' : 'bar';
    const series = Array.isArray(payload.series) ? payload.series : [];
    const datasets = series.map(function (serie, index) {
        const colour = colours[index % colours.length];
        const data = serie && Array.isArray(serie.values) ? serie.values : [];
        const label = serie && serie.label != null
            ? String(serie.label)
            : (payload.label != null ? String(payload.label) : '');
        const dataset = {
            label: label,
            data: data,
            backgroundColor: colour
        };
        if (type === 'line') {
            // A line dataset paints its stroke from borderColor; without it the
            // series is invisible even though the default bar fill is set.
            dataset.borderColor = colour;
            dataset.fill = false;
        }
        return dataset;
    });

    return {
        type: type,
        labels: Array.isArray(payload.labels) ? payload.labels : [],
        datasets: datasets,
        // One series: the card title names it, so legend stays off unless the
        // payload explicitly asks for one (historical multi-line chart).
        options: { plugins: { legend: { display: payload.legend === true } } },
        emptyLabel: payload.emptyLabel || DEFAULT_EMPTY_LABEL
    };
}

/**
 * Show the in-frame failure state. Mirrors chart-config.js setEmptyState(): the
 * canvas is hidden and a .chart-empty note takes its definite height, so the
 * card reports the failure instead of showing a silent empty rectangle.
 *
 * @param {HTMLCanvasElement} canvas the canvas that could not be drawn
 */
function showRenderError(canvas) {
    canvas.hidden = true;
    const body = canvas.closest('.chart-body');
    if (!body) {
        return;
    }
    let note = body.querySelector('.chart-empty');
    if (!note) {
        note = document.createElement('p');
        note.className = 'chart-empty';
        note.setAttribute('role', 'status');
        body.appendChild(note);
    }
    note.textContent = RENDER_ERROR_LABEL;
}

/**
 * Read and parse the payload that belongs to a canvas.
 *
 * @param {HTMLCanvasElement} canvas the marked canvas
 * @returns {object|null} parsed payload, or null after logging the failure
 */
function payloadFor(canvas) {
    const payloadId = canvas.getAttribute('data-report-chart');
    const node = payloadId ? document.getElementById(payloadId) : null;
    if (!node) {
        // Keep whatever empty state the page rendered: a missing payload is a
        // server/template bug, not something the operator can act on, so it is
        // a warning and not a red error surface.
        console.warn('[mercurius] Falta el payload "' + payloadId + '" de la gráfica "'
            + (canvas.id || '?') + '".');
        return null;
    }
    try {
        return JSON.parse(node.textContent || '');
    } catch (error) {
        console.error('[mercurius] Payload de gráfica inválido "' + payloadId + '"', error);
        showRenderError(canvas);
        return null;
    }
}

/**
 * Mount every chart under `root`. Safe to call repeatedly: create() destroys
 * the previous instance bound to the same canvas id before drawing.
 *
 * @param {Document|Element} [root] subtree to scan; defaults to the document
 */
export function mount(root) {
    const charts = window.MercuriusCharts;
    if (!charts || typeof charts.create !== 'function') {
        console.warn('[mercurius] MercuriusCharts no está disponible; no se dibujan las gráficas.');
        return;
    }

    const scope = root && typeof root.querySelectorAll === 'function' ? root : document;
    const canvases = Array.prototype.slice.call(scope.querySelectorAll('[data-report-chart]'));
    if (scope.matches && scope.matches('[data-report-chart]')) {
        canvases.push(scope);
    }

    canvases.forEach(function (canvas) {
        if (!canvas.id) {
            console.warn('[mercurius] Canvas con data-report-chart sin id; se omite.');
            return;
        }
        const payload = payloadFor(canvas);
        if (!payload) {
            return;
        }
        charts.create(canvas.id, buildConfig(payload));
    });
}

/**
 * Register the htmx delegation once. afterSwap covers the normal swap; the
 * out-of-band variant covers a chart card delivered beside the response (the
 * toast/OOB pattern this app already uses).
 */
function installHtmxBridge() {
    if (window.mercuriusReportChartReady) {
        return;
    }
    window.mercuriusReportChartReady = true;

    const remount = function (event) {
        const detail = event.detail || {};
        mount(detail.target || detail.elt || event.target);
    };
    document.addEventListener('htmx:afterSwap', remount);
    document.addEventListener('htmx:oobAfterSwap', remount);
}

/**
 * First mount, deferred one task on purpose: index.js imports this module
 * before it assigns window.MercuriusCharts and before its own DOMContentLoaded
 * handler runs applyChartDefaults(). A synchronous mount here would draw with
 * unset fonts/grid defaults; the timeout lands after boot() has finished.
 */
function bootReportCharts() {
    installHtmxBridge();
    window.setTimeout(function () {
        mount(document);
    }, 0);
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', bootReportCharts);
} else {
    bootReportCharts();
}
