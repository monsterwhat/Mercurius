// Mercurius application entry point.
// Manual index: importing index.scss here is what makes the bundler emit app-*.css
import 'htmx.org';
import Alpine from 'alpinejs';
import './print.js';
import './index.scss';
// W1 design-system layer. Imported from JS rather than from index.scss: a sass
// @import of a partial triggers a dart-sass deprecation warning that makes the
// mvnpm/esbuild sass plugin in quarkus-web-bundler fail the whole bundle with
// an opaque BundlingException. esbuild concatenates CSS in import order, so this
// still yields Bulma -> legacy layers -> Mercurius tokens + components.
import './mercurius.scss';
import { installIconHydration } from './icons.js';
import { applyChartDefaults, Chart, create as createChart, destroyAll as destroyCharts } from './chart-config.js';

// chart.js is a provided-scope org.mvnpm dependency resolvable ONLY at build
// time inside bundle entries, so Qute pages cannot import it directly.
// Exposing it on window lets inline <script type="module"> blocks on pages
// initialize charts from the bundle. W1 also publishes the shared factory as
// window.MercuriusCharts so page scripts stop redefining Chart.js defaults.
Chart.register();
window.Chart = Chart;
window.MercuriusCharts = {
    create: createChart,
    destroyAll: destroyCharts
};

window.Alpine = Alpine;
Alpine.start();

/**
 * kitBridge() — the single delegated handler behind `data-kit-open` /
 * `data-kit-close`.
 *
 * At W0 this exact listener was copy-pasted into 16 separate <script> blocks
 * across 15 templates. W1 installs it once here; W6 deletes the copies. Pages
 * need no markup change: any element carrying `data-kit-open="<modal-id>"`
 * already works, because the listener is delegated on document.body.
 */
function kitBridge() {
    if (window.mercuriusBridgeReady) {
        return;
    }
    window.mercuriusBridgeReady = true;
    document.body.addEventListener('click', function (e) {
        var opener = e.target.closest('[data-kit-open]');
        if (opener) {
            var modal = document.getElementById(opener.getAttribute('data-kit-open'));
            if (modal && window.Alpine) {
                Alpine.$data(modal).open = true;
            }
            return;
        }
        var closer = e.target.closest('[data-kit-close]');
        if (closer) {
            var root = closer.closest('.kit-modal-root');
            if (root && window.Alpine) {
                Alpine.$data(root).open = false;
            }
        }
    });
}

/**
 * kitUploadProgress() — shared upload progress behaviour for the three XML
 * upload surfaces (Inventario, Recibos, Facturas recibidas).
 *
 * htmx 2 emits `htmx:xhr:progress` natively; there is no `hx-upload-progress`
 * attribute to lean on, so this helper is the shared part only.
 *
 * Opt-in contract (additive, no page changes yet — W5 migrates the pages):
 *
 *   <form data-upload-progress="#inventario-progreso" hx-post="...">
 *     <progress id="inventario-progreso" class="progress is-primary"
 *               max="100" value="0"></progress>
 *     <button type="submit" class="button is-primary">Procesar Facturas</button>
 *   </form>
 *
 * While the upload runs the form's submit button gets `is-loading` and is
 * disabled; both are reverted on completion, error or timeout.
 */
function kitUploadProgress() {
    if (window.mercuriusUploadReady) {
        return;
    }
    window.mercuriusUploadReady = true;

    var findBar = function (elt) {
        var scope = elt && elt.querySelector ? elt : document;
        var sel = elt && elt.getAttribute && elt.getAttribute('data-upload-progress');
        if (sel) {
            return document.querySelector(sel);
        }
        return scope.querySelector('[data-upload-progress]');
    };

    var setBusy = function (form, busy) {
        if (!form) {
            return;
        }
        var buttons = form.querySelectorAll('button[type="submit"], button:not([type])');
        Array.prototype.forEach.call(buttons, function (btn) {
            btn.classList.toggle('is-loading', busy);
            btn.disabled = busy;
        });
    };

    var release = function (elt) {
        var bar = findBar(elt);
        if (bar && bar.tagName === 'PROGRESS') {
            bar.removeAttribute('aria-busy');
        }
        var form = elt && elt.closest ? elt.closest('form') : null;
        if (form) {
            setBusy(form, false);
        }
    };

    document.body.addEventListener('htmx:xhr:progress', function (evt) {
        var bar = findBar(evt.detail.elt);
        if (bar && evt.detail.lengthComputable) {
            bar.value = (evt.detail.loaded / evt.detail.total) * 100;
            bar.setAttribute('aria-busy', 'true');
        }
    });

    ['htmx:afterRequest', 'htmx:responseError', 'htmx:sendError', 'htmx:timeout'].forEach(function (name) {
        document.body.addEventListener(name, function (evt) {
            release(evt.detail.elt);
        });
    });
}

/**
 * kitPolling() — visibility-aware polling lifecycle.
 *
 * Two problems at W0:
 *   1. `hx-trigger="every 5s"` on the Tributacion countdown hammered a
 *      Hacienda-facing endpoint even while the tab was hidden.
 *   2. Nothing told the user when a background refresh had last run, so a
 *      stale board looked live.
 *
 * Opt-in contract (additive; W5 migrates the two polling pages):
 *
 *   <div id="inventario-badges"
 *        hx-get="/Mercurius/api/app/inventario/badges"
 *        hx-trigger="every 30s" hx-swap="innerHTML"
 *        data-poll
 *        data-poll-age="#inventario-poll-age">
 *     ...
 *   </div>
 *   <span id="inventario-poll-age" class="action-count"></span>
 *
 * Behaviour: stamps `data-poll-last` on every successful swap, refreshes the
 * `data-poll-age` label once per second ("Actualizado hace 12 s"), and issues an
 * immediate catch-up refresh when a hidden tab becomes visible again if the
 * interval has already elapsed. htmx still owns the interval itself and still
 * tears it down when the element is removed (htmx `cleanUpElement`); this
 * helper only adds visibility handling and the age label.
 */
function kitPolling() {
    if (window.mercuriusPollingReady) {
        return;
    }
    window.mercuriusPollingReady = true;

    var intervalMs = function (el) {
        var trigger = el.getAttribute('hx-trigger') || '';
        var m = trigger.match(/every\s+(\d+)\s*(ms|s|m)/);
        if (!m) {
            return 30000;
        }
        var n = parseInt(m[1], 10);
        return m[2] === 'ms' ? n : (m[2] === 'm' ? n * 60000 : n * 1000);
    };

    var refresh = function (el) {
        var url = el.getAttribute('hx-get');
        if (!url || !window.htmx) {
            return;
        }
        window.htmx.ajax('GET', url, {
            target: el.getAttribute('hx-target') || el,
            swap: el.getAttribute('hx-swap') || 'innerHTML'
        });
    };

    var paintAge = function (el) {
        var sel = el.getAttribute('data-poll-age');
        if (!sel) {
            return;
        }
        var out = document.querySelector(sel);
        if (!out) {
            return;
        }
        var last = parseInt(el.getAttribute('data-poll-last') || '0', 10);
        if (!last) {
            out.textContent = '';
            return;
        }
        var secs = Math.max(0, Math.round((Date.now() - last) / 1000));
        out.textContent = secs < 5 ? 'Actualizado ahora' : 'Actualizado hace ' + secs + ' s';
    };

    document.addEventListener('visibilitychange', function () {
        if (document.hidden || !window.htmx) {
            return;
        }
        Array.prototype.forEach.call(document.querySelectorAll('[data-poll]'), function (el) {
            var last = parseInt(el.getAttribute('data-poll-last') || '0', 10);
            if (!last || (Date.now() - last) >= intervalMs(el)) {
                el.setAttribute('data-poll-last', String(Date.now()));
                paintAge(el);
                refresh(el);
            }
        });
    });

    document.body.addEventListener('htmx:afterSwap', function (evt) {
        var el = evt.detail.elt;
        if (el && el.matches && el.matches('[data-poll]')) {
            el.setAttribute('data-poll-last', String(Date.now()));
        }
    });

    setInterval(function () {
        Array.prototype.forEach.call(document.querySelectorAll('[data-poll][data-poll-age]'), paintAge);
    }, 1000);
}

/**
 * kitPopup() - the shared launcher for links that open a NAMED browser window.
 *
 * Opt-in contract (additive; the two attributes are the only markup needed):
 *
 *   <a class="button is-dark"
 *      href="/Mercurius/app/pos/standalone"
 *      target="_blank"
 *      rel="noopener"
 *      data-kit-popup="nuevaFactura"
 *      data-kit-popup-features="popup=yes,width=1280,height=800,left=120,top=80">
 *     Nueva Factura
 *   </a>
 *
 * The URL is read from the link's OWN href, so the deployment root stays
 * resolved in exactly one place - {config:['quarkus.http.root-path']} in the
 * template - and this helper never has to know about it.
 *
 * Behaviour, and the reason for each branch:
 *   - Only a plain primary click is intercepted. Ctrl/Cmd/Shift+click and
 *     middle-click keep the browser default, so the user gets the tab or
 *     window THEY asked for and this page is never navigated away from.
 *   - Keyboard activation (Enter on a focused link) dispatches the very same
 *     primary click, so it opens the popup too - and it carries the user
 *     activation a popup blocker needs in order to allow it.
 *   - The named target is reused, so a second click raises the POS window
 *     that is already open instead of spawning another one.
 *   - window.open returning null means a blocker ate the popup. There the
 *     default is deliberately NOT prevented: the anchor follows its own href
 *     in a new tab because of target="_blank", so even the
 *     fallback never replaces the page the user was working in. With
 *     JavaScript off this is the only path, and it is the same one.
 */
function kitPopup() {
    if (window.mercuriusPopupReady) {
        return;
    }
    window.mercuriusPopupReady = true;

    document.body.addEventListener('click', function (e) {
        var link = e.target.closest('[data-kit-popup]');
        if (!link || e.defaultPrevented) {
            return;
        }
        if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) {
            return;
        }
        var url = link.href || link.getAttribute('href');
        if (!url) {
            return;
        }
        var popup = window.open(url,
            link.getAttribute('data-kit-popup'),
            link.getAttribute('data-kit-popup-features') || '');
        if (popup) {
            e.preventDefault();
            popup.focus();
        }
    });
}

function boot() {
    applyChartDefaults();
    installIconHydration();
    kitBridge();
    kitUploadProgress();
    kitPolling();
    kitPopup();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
} else {
    boot();
}
