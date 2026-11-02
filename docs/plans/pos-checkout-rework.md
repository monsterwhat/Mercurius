# POS checkout rework — plan (product-owner brief)

**Status:** approved by the product owner in chat; not implemented yet.
**Scope:** `pages/facturas/factura-body.html`, `pages/facturas/cart-panel.html`, `PosResource`, one new dialog fragment.
**Constraint:** no business-logic rewrite, no new backend feature. The till must stay sellable at every commit.

## 1. Decisions (verbatim intent)

1. **Remove** the "Pagos y vuelto" button from the cart panel and **remove the payment dialog** from the POS UI. The endpoint may stay for API compatibility; the operator never sees it.
2. The cart ends with: **Total a pagar** + **Cancelar venta** + **Facturar**.
3. **Facturar opens a dialog** with exactly two amounts:
   - **Colones recibidos**
   - **Dólares recibidos** (optional)
   There is **no payment-method question** (no tarjeta, no SINPE, no efectivo selector). Only "how much did they hand you".
4. The dialog shows, live: `pagado = colones + dólares × tipo de cambio` and **Vuelto** (`pagado − total`).
5. Confirming the dialog **invoices immediately** and leaves the till ready for the next sale.
6. The dialog has **Cancelar** (back to the cart) and **Facturar** (confirm).
7. "Cliente ocasional" → **"Cliente contado"** — *already done* (`73c5232`).
8. Layout: narrower capture column, wider cart — *already done* (5/12 + 7/12). Still pending: less outer margin/border so the till uses more of the window.

## 2. Bugs to fix in the same pass

| Symptom | Evidence | Where |
|---|---|---|
| Search → **Agregar** in the article picker does not add the line | Manual barcode + Agregar works | `pages/facturas/articulos-picker.html` posts to `/api/app/pos/articulo-add-form` with `hx-target="#cart-panel"` |
| **Seleccionar cliente** in the picker does not attach the client | Same pattern | `pages/facturas/client-picker.html` posts to `/api/app/pos/client-select-form`, `hx-target="#cart-panel"` |

Both share the target and fail together → the prime suspect is the `#cart-panel` element inside the **standalone popup** (it was moved into a `.column` wrapper when the POS became two-column) or a failed POST. Prove it with the network status before changing anything.

## 3. Implementation steps (one commit each)

1. **Prove the picker failure.** Playwright against `/app/pos/standalone`: log in, create an article with stock, open the picker, click Agregar, capture the POST status and the DOM before/after. Same for the client picker.
2. **Fix the pickers** once the status is known (target in the DOM tree vs. out-of-band swap).
3. **New checkout dialog** (`pages/facturas/checkout-dialog.html`): colones, dólares, `Vuelto`, Cancelar, Facturar. Alpine state for the live calculation; the exchange rate comes from the badge already rendered in the POS header (`badge.venta`).
4. **Wire Facturar** in `cart-panel.html` to open that dialog instead of posting directly; remove the "Pagos y vuelto" block and the `modal-pago` include.
5. **Facturar endpoint**: reuse the existing payment plumbing so the invoice keeps its staged payment; the dialog posts the two amounts and the server converts dollars to CRC at the rate it already uses. If the existing endpoint cannot take two currencies, add the smallest possible wrapper — do not touch invoice generation.
6. **Layout polish**: remove the outer padding/border on the POS section so the two columns fill the window.

## 4. Acceptance (checked in the browser, no new tests required by the owner)

- The POS has **no** "Pagos y vuelto" button.
- **Facturar** opens the dialog; typing only colones works; typing colones + dólares shows the right **Vuelto**.
- Confirming **invoices** and the till comes back empty for the next sale.
- **Cancelar** in the dialog returns to the cart with the lines intact; **Cancelar venta** empties the cart.
- Search → Agregar adds the line; client picker attaches the client.
- The layout fills the window; no horizontal scroll at 1440/1024/900.

## 5. Non-goals

- No change to Hacienda emission, totals, IVA, discounts or puntos.
- No removal of the payment endpoints (API parity only).
- No new dependency.
