/**
 * Mercurius icon set (W1 design-system foundation).
 *
 * SOURCE OF TRUTH for every icon in the application. Replaces the three
 * competing systems found at W0:
 *   1. FontAwesome markup (`<i class="fas fa-edit">`) that never rendered
 *      because FontAwesome is not a declared dependency.
 *   2. Numeric HTML entities used as button glyphs (&#9998; pencil,
 *      &#9211; fast-forward, &#8635; refresh, ...).
 *   3. Raw emoji / Unicode symbols rendered differently per OS.
 *
 * DESIGN CONSTRAINTS
 * ------------------
 * - No new dependency. These are hand-written paths, not an icon font.
 * - 24x24 viewBox, `fill="none"`, `stroke="currentColor"`, round caps/joins.
 *   Because the stroke is `currentColor` the icon inherits the text color of
 *   whatever contains it, which keeps Bulma's `is-primary`/`is-danger`/...
 *   button colors working without per-icon color rules.
 * - All templates in `templates/_kit/*.html` are SSR. A Qute template cannot
 *   read a JavaScript map at render time, so `_kit/icon.html` emits a marker
 *   element and `hydrateIcons()` (below, called from web/index.js) swaps in
 *   the markup. Consequences, documented in docs/ui-kit.md section 3:
 *     * Zero HTTP requests - the SVG ships inside the existing app-*.js.
 *     * Icons appear once the bundle executes (htmx-swapped content is
 *       covered by a MutationObserver).
 *     * Icon-only buttons MUST carry their own `aria-label`; the icon itself
 *       is decorative. That requirement is unchanged from the pre-W1 markup.
 *
 * ADDING AN ICON
 * --------------
 * Add one entry to PATHS below, then reference it as
 * `{#include _kit/icon name="<key>" /}`. An unknown key renders a marker with
 * `data-icon-missing` and logs one console warning naming the key, so typos
 * surface immediately instead of rendering an invisible gap.
 */

/**
 * Inner SVG markup per icon key (the <svg> wrapper is added by svgFor()).
 * Keep every path on a single line: the bundle does not minify this file and
 * multi-line paths make review diffs noisy.
 */
const PATHS = {
    // --- Primary actions -------------------------------------------------
    plus: '<path d="M12 5v14M5 12h14"/>',
    minus: '<path d="M5 12h14"/>',
    check: '<path d="M20 6L9 17l-5-5"/>',
    close: '<path d="M18 6L6 18M6 6l12 12"/>',

    // --- Row / table actions (dense, always icon-only + aria-label) ------
    edit: '<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.121 2.121 0 0 1 3 3L12 15l-4 1 1-4 9.5-9.5z"/>',
    eye: '<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/>',
    trash: '<path d="M3 6h18"/><path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2"/><path d="M19 6v14a1 1 0 0 1-1 1H6a1 1 0 0 1-1-1V6"/><path d="M10 11v6M14 11v6"/>',
    power: '<path d="M18.36 6.64a9 9 0 1 1-12.73 0"/><path d="M12 2v10"/>',
    'arrow-right': '<path d="M5 12h14"/><path d="M12 5l7 7-7 7"/>',
    'arrow-left': '<path d="M19 12H5"/><path d="M12 19l-7-7 7-7"/>',
    refresh: '<path d="M23 4v6h-6"/><path d="M1 20v-6h6"/><path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10"/><path d="M1 14l4.64 4.36A9 9 0 0 0 20.49 15"/>',

    // --- Data operations --------------------------------------------------
    search: '<circle cx="11" cy="11" r="8"/><path d="M21 21l-4.35-4.35"/>',
    filter: '<path d="M22 3H2l8 9.46V19l4 2v-8.54L22 3z"/>',
    download: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M7 10l5 5 5-5"/><path d="M12 15V3"/>',
    upload: '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><path d="M17 8l-5-5-5 5"/><path d="M12 3v12"/>',
    print: '<path d="M6 9V2h12v7"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><path d="M6 14h12v8H6z"/>',
    send: '<path d="M22 2L11 13"/><path d="M22 2l-7 20-4-9-9-4 20-7z"/>',

    // --- Domain -----------------------------------------------------------
    user: '<path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="11" r="4"/>',
    lock: '<rect x="3" y="11" width="18" height="11" rx="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/>',
    money: '<path d="M3 7h18v12a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V7z"/><path d="M3 7l2-4h14l2 4"/><circle cx="16" cy="13" r="1"/>',
    cart: '<circle cx="9" cy="21" r="1"/><circle cx="20" cy="21" r="1"/><path d="M1 1h4l2.68 13.39a2 2 0 0 0 2 1.61h9.72a2 2 0 0 0 2-1.61L23 6H6"/>',
    barcode: '<path d="M3 5v14"/><path d="M6 5v14"/><path d="M9 5v10"/><path d="M12 5v14"/><path d="M15 5v10"/><path d="M18 5v14"/><path d="M21 5v14"/>',
    package: '<path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/><path d="M3.27 6.96L12 12.01l8.73-5.05"/><path d="M12 22.08V12"/>',
    file: '<path d="M13 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V9z"/><path d="M13 2v7h7"/>',
    chart: '<path d="M18 20V10"/><path d="M12 20V4"/><path d="M6 20v-6"/>',
    gauge: '<path d="M3.5 18a9 9 0 1 1 17 0"/><path d="M12 14l4-4"/>',
    clock: '<circle cx="12" cy="12" r="10"/><path d="M12 6v6l4 2"/>',

    // --- Feedback ---------------------------------------------------------
    alert: '<path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
    info: '<circle cx="12" cy="12" r="10"/><path d="M12 16v-4"/><path d="M12 8h.01"/>'
};

/** Every key, for docs generation and typo diagnostics. */
export const ICON_NAMES = Object.keys(PATHS);

/**
 * Build the full <svg> element for a key, or null when the key is unknown.
 * @param {string} name key present in PATHS
 * @returns {string|null} svg markup, or null for an unknown key
 */
export function svgFor(name) {
    const body = PATHS[name];
    if (!body) {
        return null;
    }
    return '<svg class="mi-icon-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" '
        + 'stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" '
        + 'focusable="false">' + body + '</svg>';
}

let warned = false;

/**
 * Replace every un-hydrated icon marker inside `root` with its SVG.
 * Markers are `[data-icon]` without a rendered child, so the function is
 * idempotent and safe to call after any htmx swap.
 *
 * @param {ParentNode} [root=document] subtree to hydrate
 */
export function hydrateIcons(root) {
    const scope = root || document;
    const markers = scope.querySelectorAll('.mi-icon[data-icon]');
    markers.forEach((el) => {
        if (el.dataset.iconDone === '1') {
            return;
        }
        const svg = svgFor(el.getAttribute('data-icon'));
        if (svg === null) {
            el.setAttribute('data-icon-missing', el.getAttribute('data-icon'));
            if (!warned) {
                warned = true;
                console.warn('[mercurius] unknown icon key(s); see data-icon-missing attributes. '
                    + 'Valid keys: ' + ICON_NAMES.join(', '));
            }
            return;
        }
        el.innerHTML = svg;
        el.dataset.iconDone = '1';
    });
}

/**
 * One-time install: hydrate the initial document and keep watching for icons
 * introduced by htmx swaps. Idempotent via the window flag.
 */
export function installIconHydration() {
    if (window.mercuriusIconsReady) {
        return;
    }
    window.mercuriusIconsReady = true;
    const run = () => hydrateIcons(document);
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', run);
    } else {
        run();
    }
    document.body.addEventListener('htmx:afterSwap', (evt) => hydrateIcons(evt.target));
    new MutationObserver((mutations) => {
        mutations.forEach((m) => m.addedNodes.forEach((n) => {
            if (n.nodeType === 1) {
                if (n.classList && n.classList.contains('mi-icon')) {
                    hydrateIcons(n.parentNode || document);
                } else if (n.querySelector) {
                    hydrateIcons(n);
                }
            }
        }));
    }).observe(document.body, { childList: true, subtree: true });
}
