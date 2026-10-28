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
import { applyChartDefaults, Chart, create as createChart, destroyAll as destroyCharts, resolveColour, currency, number, date } from './chart-config.js';
// P2 Phase 2: the shared chart bootstrap (reads the server payload and mounts /
// re-mounts charts). A static import is hoisted above the
// `window.MercuriusCharts = ...` assignment below no matter where it is
// written, so the module deliberately reads nothing at import time: its first
// mount is deferred until after boot() has applied the chart defaults.
import './report-chart.js';

// chart.js is a provided-scope org.mvnpm dependency resolvable ONLY at build
// time inside bundle entries, so Qute pages cannot import it directly.
// Exposing it on window lets inline <script type="module"> blocks on pages
// initialize charts from the bundle. W1 also publishes the shared factory as
// window.MercuriusCharts so page scripts stop redefining Chart.js defaults.
Chart.register();
window.Chart = Chart;
window.MercuriusCharts = {
    create: createChart,
    destroyAll: destroyCharts,
    resolveColour: resolveColour,
    currency: currency,
    number: number,
    date: date
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
                // Remember the control that asked, so the dialog can return
                // focus to it even in browsers that do not focus a button on
                // mouse click (Safari). _kit/modal's x-init reads this as a
                // fallback.
                window.mercuriusLastOpener = opener;
                Alpine.$data(modal).open = true;
                // A trigger rendered by _kit/modal focuses the card in its own
                // @click, but a row button that only carries data-kit-open
                // never touches it: the dialog opened with focus still on the
                // page behind the overlay. Next tick is when Alpine has
                // applied .is-active and the card is displayable.
                var card = modal.querySelector('.modal-card');
                if (card) {
                    var focusCard = function () {
                        card.focus({ preventScroll: true });
                    };
                    if (typeof window.Alpine.nextTick === 'function') {
                        window.Alpine.nextTick(focusCard);
                    } else {
                        window.setTimeout(focusCard, 30);
                    }
                }
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

    // The busy state was only ever RELEASED: setBusy(form, true) did not exist
    // anywhere in this helper, so an upload never showed is-loading, the button
    // stayed enabled and a second click could post the same file again.
    document.body.addEventListener('htmx:beforeRequest', function (evt) {
        var elt = evt.detail.elt;
        if (!elt || !elt.closest) {
            return;
        }
        var form = elt.tagName === 'FORM' ? elt : elt.closest('form');
        if (!form || !findBar(form)) {
            return;
        }
        var bar = findBar(form);
        if (bar && bar.tagName === 'PROGRESS') {
            bar.value = 0;
            bar.setAttribute('aria-busy', 'true');
        }
        setBusy(form, true);
    });

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

/**
 * kitToasts() — the ONE toast lifecycle.
 *
 * The markup lives in _kit/toast-item; this helper binds every
 * `.notification[data-toast]` that appears inside #toast-container, whether it
 * was server-rendered or appended by an hx-swap-oob response. It registers a
 * MutationObserver once, so a swapped toast never installs a second copy of
 * the same script (the previous inline version in the template did).
 *
 * Lifetime: 5s for a success/info/warning, indefinite for an error. A failure
 * is the one message the operator may have to read twice and act on; making it
 * disappear mid-sentence is a silent failure of its own. The countdown pauses
 * while the pointer or the keyboard is on the toast.
 */
function kitToasts() {
    if (window.mercuriusToastsReady) {
        return;
    }
    window.mercuriusToastsReady = true;

    var bind = function (el) {
        if (el.dataset.toastBound) {
            return;
        }
        el.dataset.toastBound = '1';

        var remaining = 5000;
        var startedAt = Date.now();
        var timer = null;
        var severity = (el.getAttribute('data-toast-severity') || '').toLowerCase();
        // A failure is the one message the operator may have to read twice and
        // act on; it stays until dismissed. The pause/resume handlers must not
        // restart its countdown on mouseleave either.
        var persistent = severity === 'error';

        var dismiss = function () {
            if (el.dataset.toastLeaving) {
                return;
            }
            el.dataset.toastLeaving = '1';
            if (timer) {
                window.clearTimeout(timer);
                timer = null;
            }
            el.classList.add('is-leaving');
            window.setTimeout(function () {
                el.remove();
            }, 180);
        };

        var pause = function () {
            if (timer) {
                window.clearTimeout(timer);
                timer = null;
                remaining = Math.max(1000, remaining - (Date.now() - startedAt));
            }
        };

        var resume = function () {
            if (persistent || timer || el.dataset.toastLeaving) {
                return;
            }
            startedAt = Date.now();
            timer = window.setTimeout(dismiss, remaining);
        };

        var closeBtn = el.querySelector('.delete');
        if (closeBtn) {
            closeBtn.addEventListener('click', dismiss);
        }

        el.addEventListener('mouseenter', pause);
        el.addEventListener('mouseleave', resume);
        el.addEventListener('focusin', pause);
        el.addEventListener('focusout', resume);

        if (persistent) {
            return;
        }
        resume();
    };

    var attach = function () {
        var container = document.getElementById('toast-container');
        if (!container) {
            return;
        }
        Array.prototype.forEach.call(
            container.querySelectorAll('.notification[data-toast]'),
            bind
        );
        new MutationObserver(function (mutations) {
            mutations.forEach(function (mutation) {
                Array.prototype.forEach.call(mutation.addedNodes, function (node) {
                    if (node.nodeType === 1 && node.classList.contains('notification')) {
                        bind(node);
                    }
                });
            });
        }).observe(container, { childList: true });
    };

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', attach);
    } else {
        attach();
    }
}

/**
 * kitTableScroll() — the scroll affordance for a wide data table.
 *
 * Below 768px `_kit/data-table` keeps its natural width and the CONTAINER
 * scrolls. Nothing used to indicate that more columns existed, so a clipped
 * column read as a broken table. This stamps `is-scrollable` on a frame that
 * really overflows and `is-scrolled-end` once its right edge is reached; the
 * stylesheet draws the edge shadow only while both are true, so the hint never
 * lies and disappears when the table fits.
 *
 * The frame is already focusable (tabindex="0" in the template) for keyboard
 * scrolling; this helper does not change focus.
 */
function kitTableScroll() {
    if (window.mercuriusTableScrollReady) {
        return;
    }
    window.mercuriusTableScrollReady = true;

    var paint = function (frame) {
        var overflow = frame.scrollWidth - frame.clientWidth - frame.scrollLeft;
        frame.classList.toggle('is-scrollable', frame.scrollWidth > frame.clientWidth + 1);
        frame.classList.toggle('is-scrolled-end', overflow <= 1);
    };

    var scan = function () {
        Array.prototype.forEach.call(
            document.querySelectorAll('.table-container[data-kit-table]'),
            function (frame) {
                paint(frame);
                if (!frame.dataset.kitScrollObserved && typeof ResizeObserver === 'function') {
                    frame.dataset.kitScrollObserved = '1';
                    new ResizeObserver(function () {
                        paint(frame);
                    }).observe(frame);
                }
            }
        );
    };

    // Scroll does not bubble; capture it at the document so every frame (and
    // every frame swapped in later) is covered by one listener.
    document.addEventListener('scroll', function (event) {
        var frame = event.target;
        if (frame && frame.matches && frame.matches('.table-container[data-kit-table]')) {
            paint(frame);
        }
    }, true);

    window.addEventListener('resize', scan);
    document.body.addEventListener('htmx:afterSwap', scan);
    document.body.addEventListener('htmx:afterSettle', scan);
    scan();
}

/**
 * mercuriusNotify() — the one place JS creates a toast.
 *
 * The markup mirrors _kit/toast-item (notification + delete + data-toast), so
 * the shared lifecycle in kitToasts binds it exactly like a server-rendered
 * one. The severity token is the same lowercase vocabulary as the templates.
 * Returns false when there is no toast container, and logs the message so a
 * failure is never invisible.
 */
function mercuriusNotify(message, severity) {
    var container = document.getElementById('toast-container');
    if (!container) {
        console.error('[mercurius] ' + message);
        return false;
    }
    var kind = 'is-danger';
    if (severity === 'success') {
        kind = 'is-success';
    } else if (severity === 'warning' || severity === 'warn') {
        kind = 'is-warning';
    } else if (severity === 'info') {
        kind = 'is-info';
    }
    var toast = document.createElement('div');
    toast.className = 'notification ' + kind;
    toast.setAttribute('role', kind === 'is-danger' ? 'alert' : 'status');
    toast.setAttribute('data-toast', '');
    toast.setAttribute('data-toast-severity', kind === 'is-danger' ? 'error' : severity || 'info');
    var close = document.createElement('button');
    close.className = 'delete';
    close.type = 'button';
    close.setAttribute('aria-label', 'Cerrar notificación');
    toast.appendChild(close);
    toast.appendChild(document.createTextNode(message));
    container.appendChild(toast);
    return true;
}

window.mercuriusNotify = mercuriusNotify;

/**
 * kitHtmlErrorSwap() — let a 4xx HTML response render.
 *
 * htmx 2 only swaps 2xx by default. Several resources answer 400/422 with an
 * HTML validation fragment (inventario failureWithToast, compras
 * redisplayForm / redisplayEstado) plus an out-of-band toast, so the operator
 * saw nothing at all: the form was not re-rendered and the error vanished.
 * This handler flips shouldSwap for a 4xx whose body really is HTML and clears
 * isError so htmx does not ALSO fire the generic transport toast in
 * kitHttpErrors (the fragment carries the precise message).
 *
 * A JSON 4xx is deliberately left alone: it has no markup to swap, and the
 * generic toast is the only feedback it can give.
 */
function kitHtmlErrorSwap() {
    if (window.mercuriusHtmlErrorSwapReady) {
        return;
    }
    window.mercuriusHtmlErrorSwapReady = true;

    document.body.addEventListener('htmx:beforeSwap', function (evt) {
        var detail = evt.detail;
        var xhr = detail.xhr;
        if (!xhr || xhr.status < 400 || xhr.status >= 500) {
            return;
        }
        var contentType = xhr.getResponseHeader('Content-Type') || '';
        if (!/text\/html/i.test(contentType)) {
            return;
        }
        detail.shouldSwap = true;
        detail.isError = false;
    });
}

/**
 * kitHttpErrors() — one visible failure state for every HTMX request.
 *
 * A failed filter, an expired dialog body or a save that never reached the
 * server used to do nothing at all: htmx logs to the console, the page stays
 * exactly as it was, and the operator cannot tell a slow request from a broken
 * one. This listener turns the three transport failures into the same styled
 * toast the rest of the app uses, and keeps the console error for diagnosis.
 *
 * No business message is invented: the copy names the failure class, not a
 * cause it cannot know. A 4xx whose body is an HTML validation fragment is
 * swapped by kitHtmlErrorSwap instead and carries its own precise toast; a 5xx
 * or a JSON error never swaps, so this is the only feedback the operator gets.
 */
function kitHttpErrors() {
    if (window.mercuriusHttpErrorsReady) {
        return;
    }
    window.mercuriusHttpErrorsReady = true;

    var last = { message: '', at: 0 };

    var notify = function (message) {
        var now = Date.now();
        if (message === last.message && now - last.at < 4000) {
            return;
        }
        last = { message: message, at: now };
        mercuriusNotify(message, 'error');
    };

    var statusText = function (evt) {
        var xhr = evt.detail && evt.detail.xhr;
        return xhr && xhr.status ? ' (error ' + xhr.status + ')' : '';
    };

    document.body.addEventListener('htmx:responseError', function (evt) {
        var message = 'No se pudo completar la operación' + statusText(evt) + '. Intente de nuevo.';
        console.error('[mercurius] HTMX response error', evt.detail && evt.detail.xhr && evt.detail.xhr.status);
        notify(message);
    });

    document.body.addEventListener('htmx:sendError', function (evt) {
        console.error('[mercurius] HTMX send error', evt.detail);
        notify('No hay conexión con el servidor. Verifique la red e intente de nuevo.');
    });

    document.body.addEventListener('htmx:timeout', function (evt) {
        console.error('[mercurius] HTMX timeout', evt.detail);
        notify('La operación tardó demasiado. Intente de nuevo.');
    });
}

function boot() {
    applyChartDefaults();
    installIconHydration();
    kitBridge();
    kitUploadProgress();
    kitPolling();
    kitPopup();
    kitToasts();
    kitTableScroll();
    kitHtmlErrorSwap();
    kitHttpErrors();
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
} else {
    boot();
}
