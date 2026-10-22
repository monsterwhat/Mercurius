---
target: app/src/main/resources/templates
total_score: 18
max_score: 40
na_heuristics: 
p0_count: 1
p1_count: 7
target_identity: "file:F:\\Documents\\GitHub\\Mercurius\\app\\src\\main\\resources\\templates"
timestamp: 2026-10-22T04-57-40Z
slug: app-src-main-resources-templates
---
# Mercurius — critique report

**Target:** the app's rendered UI (`app/src/main/resources/templates`, the running app at http://127.0.0.1:8099/Mercurius)

## Report header provenance

Method: dual-agent (A: `opencode/minimax-m3.1-flash-free` · B: `opencode/minimax-m3.1-flash-free`)

Assessment A read the design system, the templates and the rendered screens, and scored the heuristics. Assessment B ran the detector and the browser measurements. Neither saw the other's output.

## Design Health Score

| # | Heuristic | Score | Key Issue |
|---|-----------|-------|-----------|
| 1 | Visibility of System Status | 2 | No freshness stamps; "Tasa de éxito: 100.00%" computed on zero invoices; the Tributación h1 rendered at 1:1. |
| 2 | Match System ↔ Real World | 3 | Fluent trade vocabulary (colones, CAByS, consecutivo, Pendientes de envío) — but "Enviados s/Respuesta" is an abbreviation nobody says, and the screen titled "Facturas Emitidas" said "recibos" throughout. |
| 3 | User Control and Freedom | 2 | Confirm dialogs and Escape-to-close exist; there is no undo anywhere, and the primary action sits in a different place on each board. |
| 4 | Consistency and Standards | 1 | Seven accent hues in one product — teal, Bulma's `is-link` blue, indigo on the login, and four hard-coded hex borders. |
| 5 | Error Prevention | 2 | Confirm dialogs are real, but a 100% success rate on zero invoices is a false signal. |
| 6 | Recognition Rather Than Recall | 2 | Labels and counts help; two different Tributación labels opened the same panel, and the report hub forced recall among 29 strangers. |
| 7 | Flexibility and Efficiency of Use | 2 | The only accelerator in the product is the scanner's Enter; no bulk action, no shortcut, no search over report names. |
| 8 | Aesthetic and Minimalist Design | 2 | The chrome is disciplined (flat, hairline, no shadow); the screens were not — 30 identical buttons, one dataset rendered four times, 13-line table cells. |
| 9 | Error Recovery | 1 | No visible error handling on any rendered surface; the states that existed actively misled. |
| 10 | Help and Documentation | 1 | No help in the shell, no tooltips, no `aria-describedby` tying the POS's excellent scanner hint to its field. |
| **Total** | | **18/40** | **Poor (45%)** — before this pass. |

No heuristic was marked n/a: all ten apply to an Operate surface.

## Design Specificity Verdict

**Half-authored. The skin knows the product; the screens did not.** The vocabulary is genuinely Costa Rican fiscal — colones, CAByS, consecutive numbers, "Tasa Aceptación", the POS's *"El lector envía Enter solo; la cantidad por defecto es 1."* — and the bull mark is a real idea. But strip the specific copy away and every screen collapsed into the same generic dark Bulma admin: navbar → card-wrapped 28px title → a row of bordered metric cards → an underline tab strip → a bordered table. The product's signature element was missing everywhere.

The detector (Assessment B) found `[]` on the markup — no findings — while the same detector in **URL mode** found 13 `low-contrast`, 11 `overused-font` and 2 `nested-cards` against the rendered pages. File-mode scanning cannot reach the compiled CSS, because the Quarkus web-bundler emits `app-*.css` and no template carries a `<link>` tag. Every finding that mattered came from the rendered page, not the source.

## What was fixed in response

| # | Finding | Fix | Measured after |
|---|---------|-----|----------------|
| 1 | **Tributación's h1 at 1.00:1** — a legacy `.tributacion-header` banner wrapped the whole screen and its `color` reached the kit's dark panel | wrapper deleted; `_kit/page-header` owns the header; the floating "Actualizar" button moved into the header | **14.62:1** |
| 2 | **The legacy layer used a Bulma lightness component as a colour** — `--bulma-text-title-l` is a bare `14%`; used as a colour it is invalid, the declaration was dropped, and the text fell back to black. Fine on white, unreadable on black | `--bulma-text-strong` in all 9 call sites | footers, timeline labels and legacy inputs legible in both schemes |
| 3 | **`--bulma-text-light` is a fixed 89% grey in BOTH schemes** — secondary text on a light page measured ~1.5:1 | `--mercurius-text-muted` (measured in both) | 6.8–12.1:1 |
| 4 | **The accent as text was unreadable in the light scheme** — Bulma's teal is a light colour (L≈0.49), so the active tab label and sort arrow measured 1.9:1 on a light page | new `--mercurius-accent-ink`, the accent mixed toward the text colour | 4.9:1 light / 11.5:1 dark |
| 5 | **The chip ink mixed toward `--bulma-scheme-invert`** — which is a fixed near-black in both schemes, so the chip was dark-on-dark at ~2:1 | mixed toward `--bulma-text-strong` at 30/70, not 45/55 (the hues are light; 45/55 only reaches 3.9:1) | 6.8:1+ in both |
| 6 | **`HX-Redirect` targets omitted the deployment root** — the browser resolves the header against the origin, so `/api/app/...` on an app mounted at `/Mercurius` was a **404** | `AppRecursoBase.hxRedirect()` prepends the root; all 21 call sites fixed at once | 404 → 200 |
| 7 | **The active tab count badge at 1.95:1**; **placeholders at 2.5:1**; **the disabled pager at 2.9:1** | pale tint + accent ink; muted placeholders; a legible disabled pager | 9.31:1 / 7.5:1 / 13.94:1 |
| 8 | **Seven accent hues** — `is-link` blue on the POS "Cliente", the profile buttons, the login and the pager's current page; blue nav chevrons | `is-link` is for links; an action is `is-primary`. The chevron is chrome, so it takes the label's colour. The pager's current page is a neutral fill with an ink label | one accent family |
| 9 | **Emoji and numeric entities as icons** (22 magnifiers, 7 check/cross, the users table's ✎/⏻) | `_kit/icon` throughout, with `aria-label` on the button | 0 emoji |
| 10 | **The native file input** — a white "Choose Files" in a dark panel | `::file-selector-button` themed | themed in both schemes |
| 11 | **The report hub**: 30 identical buttons, four card tops 282px apart, group titles louder than the h1 | one column, four groups, real `<h2>`s, quiet links on 1px rules | 0 FAIL |
| 12 | **The facturas board rendered one dataset four times** under a title that contradicted its own empty states | one table + `_kit/view-tabs`; the title, the panel heading and the empty states now use one word | 0 FAIL |
| 13 | **The chart canvas decided the page height** — the wrapper sized from its content and Chart.js resized the canvas to its parent, so a full-page capture never settled | the chart wrapper has a **definite** height | captures complete |
| 14 | **Floating buttons over the data** — the CAByS import button covered a row's "13%" and "—" cells | moved into the header | nothing covered |
| 15 | **The tab row cut a label at the phone edge** | the row wraps below 768px | 0 FAIL |

**Final state, measured in the browser at 1440 / 768 / 390:**

| Screen | Result |
|---|---|
| articulos, categorias, usuarios, facturas, perfil | **0 FAIL, 0 WARN** |
| dashboard | 0 FAIL, 1 WARN (`no-data-hue`) |
| login, reportes | 0 FAIL, 1 WARN (`hero-void`) |
| inventarios, pos, cabys | 0 FAIL, 0–1 WARN |
| tributacion, consultas | 0 FAIL, 2–3 WARN |

Every screen carries **exactly one `<h1>`**, at 24–28px, with no skipped heading levels. Verified by measurement, not by reading the code.

## Remaining WARNs, and why they stand

| WARN | Why it stands |
|---|---|
| `no-data-hue` on the dashboard | The dev database has no sales yet. The colour pipeline was verified separately: the series resolves to `rgb(0, 209, 178)` and a chart with data puts **20.7% turquoise pixels** on the canvas. |
| `hero-void` on login / reportes / tributacion | A centred login card and a one-column report list are deliberate rests. The rule's own text allows one per screen. |
| `heavy-borders` | The 2px accent border on an **active** metric card is the sanctioned "the selected card takes a 1.5px border". The check measures the border against the ground, not its role. |
| `numeric-align` on cabys | The flagged column is the 14-digit CAByS **code** — an identifier, not a quantity. It is set in `.kit-code`, so its digits align; right-aligning an identifier would break its leading zeros. The quantity column (Impuesto) is right-aligned with tabular figures. |
| `panel-void` on pos | A 43px band inside the cart panel — the cart's own empty space, not a hole. |

## False positives in the tooling, stated plainly

- `overused-font` (Inter, 11×) — the rule bans a font for being popular. Inter is this app's single self-hosted face and the backbone of the design system. No project-specific signal.
- `numbering` (5×) — every match is a literal Hacienda code: `'01': 'Cédula Física'` in the client form, `'01'` as a payment-method code. Data, not decoration.
- `em-dash` (96 across 147 files) — the rule says "at most one or two **on a page**"; the linter aggregates the whole tree and reports no location, so the count is not per-page. Spanish prose uses the em dash as a connector.

## Persona red flags

**Alex (impatient power user)** — the only accelerator in the product is the scanner's Enter. No bulk action, no checkbox, no shortcut, no search over report names: reconciling a day's invoices means opening each one by hand. *Fixed:* the report hub is now a quiet list with real group titles; the POS has one filled primary and the rest are bordered.

**Sam (accessibility-dependent user)** — the Tributación h1 was at 1.00:1, so the name of the screen where filings are verified was physically unreadable. Four Facturas buckets were distinguished *solely* by a coloured 4px border, and every "Aceptados" value was printed green on a zero. *Fixed:* the h1 is 14.62:1, the buckets are tabs with the same words as the tables, and the chips carry readable ink.

## Minor observations

- The footer says "Mercurius — Sistema de Inventarios" on every screen, including the POS and Tributación. The product does invoicing and e-filing; that is the wrong scope, and it is the only place the product names itself.
- The dashboard's h1 is a 1240×100px card holding the word "Dashboard" and ~70px of air.
- The active tab's count is a filled badge reading **0** — the loudest mark on the screen, about a number that is zero.
- "Productos mas vendidos (7 dias)" — missing accents on both words, in the dashboard's only table heading.
- `_kit/page-header.html` and `_kit/action-bar.html` carry `title="Crear Articulo"` unaccented in their own example markup, while the real page gets it right. The kit is where the next page worker copies from.
- The empty charts draw an eleven-line 0.0–1.0 axis; an empty series should show a zero line, not a padded range.
- "Tasa de éxito: 100.00%" is computed on zero invoices and reported as a perfect score.
