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
    Chart.defaults.animation.duration = 350;
    Chart.defaults.plugins.legend.labels.boxWidth = 12;
    Chart.defaults.plugins.legend.labels.boxHeight = 12;
    Chart.defaults.plugins.legend.labels.usePointStyle = true;
    Chart.defaults.plugins.tooltip.backgroundColor = surface;
    Chart.defaults.plugins.tooltip.titleColor = text;
    Chart.defaults.plugins.tooltip.bodyColor = text;
    Chart.defaults.plugins.tooltip.borderColor = border;
    Chart.defaults.plugins.tooltip.borderWidth = 1;
    Chart.defaults.plugins.tooltip.padding = 10;
    Chart.defaults.plugins.tooltip.cornerRadius = 6;
    Chart.defaults.plugins.tooltip.displayColors = true;
    Chart.defaults.elements.line.borderWidth = 2;
    Chart.defaults.elements.point.radius = 3;
    Chart.defaults.elements.point.hoverRadius = 5;
    Chart.defaults.elements.bar.borderWidth = 0;
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
 * Create (or replace) a chart bound to a canvas id.
 *
 * @param {string} canvasId DOM id of the <canvas>
 * @param {object} config partial Chart.js config: type, labels, datasets, options
 * @returns {Chart|null} the chart instance, or null when the canvas is absent
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
    const spec = Object.assign({}, config, { options: withScaleDefaults(config.options) });
    const chart = new Chart(canvas.getContext('2d'), spec);
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
