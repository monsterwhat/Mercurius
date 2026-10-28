/**
 * Shared Chart.js configuration layer (W1 design-system foundation).
 *
 * Chart.js is the ONLY sanctioned chart implementation. At W0 the dashboard
 * used Chart.js while `reportes/tendencias`, `reportes/rendimiento` and
 * `reportes/pronosticos` each hand-drew a 2D canvas chart, producing three
 * different visual languages for the same job. This module centralises the
 * defaults so those pages become visually identical once migrated (W4).
 *
 * All colours are read from the live Bulma custom properties, so charts follow
 * the active colour scheme instead of hard-coding hex values.
 *
 * Usage from a page's inline <script type="module">:
 *
 *     const chart = MercuriusCharts.create('grafica-ventas-hora', {
 *         type: 'bar',
 *         labels: hourly.labels,
 *         datasets: [{ label: 'Ventas', data: hourly.ventas }]
 *     });
 *
 * Pages supply labels, datasets, type and an optional `options` override.
 * They must NOT redefine fonts, grid colours, tooltip look or locale - that is
 * the whole point of this file.
 */

import { Chart, registerables } from 'chart.js';

Chart.register(...registerables);

/** Read a CSS custom property off :root (empty string when unset). */
function cssVar(name, fallback) {
    if (typeof getComputedStyle !== 'function') {
        return fallback;
    }
    const v = getComputedStyle(document.documentElement).getPropertyValue(name);
    return v && v.trim() ? v.trim() : fallback;
}

/**
 * Resolve a CSS custom property to a colour the canvas can actually paint.
 *
 * <b>Why this exists.</b> `getPropertyValue` on an UNREGISTERED custom
 * property returns the token stream exactly as it was written, not the
 * substituted value: `--mercurius-chart-series-1: var(--bulma-primary)` reads
 * back as the string "var(--bulma-primary)". Handing that to a canvas makes
 * `fillStyle` an invalid value, so the assignment is ignored and the chart
 * draws in the canvas default black - which on the dark ground is invisible.
 * The charts measured 0.0% coloured pixels before this.
 *
 * The fix is to paint the token into an element and read the COMPUTED colour,
 * which the browser has already substituted.
 *
 * @param {string} cssValue the raw token stream, e.g. "var(--bulma-primary)"
 * @param {string} fallback used when the value cannot be resolved
 * @returns {string} a colour the 2D canvas understands, e.g. "rgb(72, 95, 199)"
 */
export function resolveColour(cssValue, fallback) {
    if (!cssValue || typeof document === 'undefined') {
        return fallback;
    }
    const probe = document.createElement('span');
    probe.style.display = 'none';
    probe.style.color = cssValue;
    document.body.appendChild(probe);
    const resolved = getComputedStyle(probe).color;
    probe.remove();
    // A colour the browser could not parse comes back as the same string.
    return resolved && resolved !== cssValue ? resolved : fallback;
}

/** rgba() from a hex custom property, e.g. '#3298dc' + 0.35. */
function withAlpha(value, alpha) {
    const hex = value.trim();
    if (hex.startsWith('#')) {
        const h = hex.length === 4
            ? '#' + hex[1] + hex[1] + hex[2] + hex[2] + hex[3] + hex[3]
            : hex;
        const n = parseInt(h.slice(1), 16);
        // eslint-disable-next-line no-bitwise
        return 'rgba(' + ((n >> 16) & 255) + ',' + ((n >> 8) & 255) + ',' + (n & 255) + ',' + alpha + ')';
    }
    return hex;
}

/** Locale-aware money. Mercurius is Costa Rica: colones (CRC, U+20A1). */
export function currency(value) {
    const n = Number(value);
    if (!isFinite(n)) {
        return String(value);
    }
    return new Intl.NumberFormat('es-CR', {
        style: 'currency',
        currency: 'CRC',
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    }).format(n);
}

/** Locale-aware plain number. */
export function number(value, decimals) {
    const n = Number(value);
    if (!isFinite(n)) {
        return String(value);
    }
    return new Intl.NumberFormat('es-CR', {
        minimumFractionDigits: decimals || 0,
        maximumFractionDigits: decimals === undefined ? 2 : decimals
    }).format(n);
}

/** Short es-CR date (dd/mm/yyyy). Input: ISO string or epoch millis. */
export function date(value) {
    if (value === null || value === undefined || value === '') {
        return '';
    }
    const d = value instanceof Date ? value : new Date(value);
    if (isNaN(d.getTime())) {
        return String(value);
    }
    return new Intl.DateTimeFormat('es-CR', {
        day: '2-digit',
        month: '2-digit',
        year: 'numeric'
    }).format(d);
}

/**
 * Apply the Mercurius defaults to Chart.js. Called once at bundle start.
 * Re-reads CSS variables so the palette tracks the active Bulma scheme.
 */
export function applyChartDefaults() {
    const text = cssVar('--bulma-text', '#4a4a4a');
    const border = cssVar('--bulma-border', '#dbdbdb');
    const surface = cssVar('--bulma-scheme-main', '#ffffff');

    Chart.defaults.font.family = getComputedStyle(document.body)
        .getPropertyValue('font-family') || 'system-ui, sans-serif';
    Chart.defaults.font.size = 12;
    Chart.defaults.color = text;
    Chart.defaults.borderColor = border;
    Chart.defaults.maintainAspectRatio = false;
    Chart.defaults.responsive = true;

    // Animation. A chart that grows out of nothing delays the number the
    // operator came for, so the draw is short and, when the OS asks for
    // reduced motion, absent altogether.
    const reduceMotion = typeof window.matchMedia === 'function'
        && window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    Chart.defaults.animation.duration = reduceMotion ? 0 : 220;
    Chart.defaults.animation.easing = 'easeOutQuart';

    Chart.defaults.plugins.legend.labels.boxWidth = 12;
    Chart.defaults.plugins.legend.labels.boxHeight = 12;
    Chart.defaults.plugins.legend.labels.usePointStyle = true;
    Chart.defaults.plugins.legend.labels.padding = 14;
    Chart.defaults.plugins.tooltip.backgroundColor = surface;
    Chart.defaults.plugins.tooltip.titleColor = text;
    Chart.defaults.plugins.tooltip.bodyColor = text;
    Chart.defaults.plugins.tooltip.borderColor = border;
    Chart.defaults.plugins.tooltip.borderWidth = 1;
    Chart.defaults.plugins.tooltip.padding = 10;
    Chart.defaults.plugins.tooltip.cornerRadius = 6;
    Chart.defaults.plugins.tooltip.displayColors = true;
    Chart.defaults.elements.line.borderWidth = 2;
    // No point markers on a line. A dot on every sample turns a trend into
    // a row of beads and adds ink without adding information; the hover dot
    // is enough to read a value.
    Chart.defaults.elements.point.radius = 0;
    Chart.defaults.elements.point.hoverRadius = 4;
    Chart.defaults.elements.bar.borderWidth = 0;
    // Hovering anywhere on the plot reads the whole column at once, which is
    // how anyone actually uses a chart.
    Chart.defaults.interaction = { mode: 'index', intersect: false };
}

/** Merge the Mercurius scale defaults into a partial page config. */
function withScaleDefaults(options) {
    const border = cssVar('--bulma-border', '#dbdbdb');
    const base = {
        grid: { color: withAlpha(border, 0.6), drawBorder: false },
        border: { display: false },
        ticks: { padding: 6 }
    };
    const user = options || {};
    const scales = user.scales || {};
    return Object.assign({}, user, {
        scales: Object.keys(scales).reduce((acc, key) => {
            acc[key] = Object.assign({}, base, scales[key]);
            return acc;
        }, {})
    });
}

const registry = new Map();

/**
 * A chart with no numeric samples has nothing to draw. Zero is a real value
 * when it sits next to a non-zero sample (the flat zero line is meaningful),
 * but an all-zero series would otherwise create a 0-1.0 axis with no visible
 * mark: it reads as a broken chart. An all-zero series therefore uses the
 * empty state; a mixed series keeps its real zero points.
 */
function numericValue(value) {
    if (value === null || value === undefined || value === '') {
        return null;
    }
    if (typeof value === 'object') {
        // Chart.js point objects: { x, y } (scatter/line) or { y }.
        if (value.y !== undefined && value.y !== null) {
            return numericValue(value.y);
        }
        if (value.x !== undefined && value.x !== null) {
            return numericValue(value.x);
        }
        return null;
    }
    const n = Number(value);
    return Number.isFinite(n) ? n : null;
}

function hasData(config) {
    const datasets = config.datasets
        || (config.data && config.data.datasets)
        || [];
    for (const dataset of datasets) {
        for (const value of (dataset.data || [])) {
            const n = numericValue(value);
            if (n !== null && n !== 0) {
                return true;
            }
        }
    }
    return false;
}

/**
 * Show or clear the in-frame empty state. The message lives inside the
 * .chart-body, so it occupies the same definite height as the canvas and
 * cannot spill onto the next table (the defect the old markup had).
 */
function setEmptyState(canvas, empty, label) {
    const body = canvas.closest('.chart-body');
    if (!body) {
        return;
    }
    canvas.hidden = empty;
    let note = body.querySelector('.chart-empty');
    if (!empty) {
        if (note) {
            note.remove();
        }
        return;
    }
    if (!note) {
        note = document.createElement('p');
        note.className = 'chart-empty';
        // The canvas is hidden, so this text is the chart for assistive tech.
        note.setAttribute('role', 'status');
        body.appendChild(note);
    }
    note.textContent = label || 'Sin datos para el período';
}

/**
 * Create (or replace) a chart bound to a canvas id.
 *
 * Returns null when the canvas is absent OR when the config has no numeric
 * sample, in which case the frame shows its empty state instead of a chart.
 *
 * @param {string} canvasId DOM id of the <canvas>
 * @param {object} config partial Chart.js config: type, labels, datasets, options
 * @param {string} [config.emptyLabel] optional empty-state copy
 * @returns {Chart|null} the chart instance, or null
 */
export function create(canvasId, config) {
    const canvas = document.getElementById(canvasId);
    if (!canvas) {
        return null;
    }
    const existing = registry.get(canvasId);
    if (existing) {
        existing.destroy();
        registry.delete(canvasId);
    }

    if (!hasData(config)) {
        setEmptyState(canvas, true, config.emptyLabel);
        return null;
    }
    setEmptyState(canvas, false);

    const context = canvas.getContext('2d');
    if (!context) {
        // Not silent: without a context there is no chart and the frame must
        // say so instead of leaving an empty white rectangle.
        console.error('[mercurius] 2D canvas context unavailable for chart "' + canvasId + '"');
        setEmptyState(canvas, true, 'No se pudo dibujar el gráfico');
        return null;
    }

    // Chart.js expects the payload under config.data. Every page passes the
    // convenient flat form ({ type, labels, datasets }); wrapping it here keeps
    // that API and fixes the old bug where Chart.js ignored the flat fields and
    // drew empty datasets (0-1.0 axes on every chart).
    const data = config.data ? Object.assign({}, config.data) : {};
    if (data.labels === undefined) {
        data.labels = config.labels || [];
    }
    if (data.datasets === undefined) {
        data.datasets = config.datasets || [];
    }
    const spec = Object.assign({}, config, {
        data,
        options: withScaleDefaults(config.options)
    });
    // emptyLabel is our copy, not a Chart.js option; the flat labels/datasets
    // are now inside data.
    delete spec.emptyLabel;
    delete spec.labels;
    delete spec.datasets;
    const chart = new Chart(context, spec);
    registry.set(canvasId, chart);
    return chart;
}

/**
 * Destroy every tracked chart. Call before a full page section that removes
 * canvases is swapped out, so Chart.js does not leak listeners on detached
 * nodes.
 */
export function destroyAll() {
    registry.forEach((chart) => chart.destroy());
    registry.clear();
}

export { Chart };
