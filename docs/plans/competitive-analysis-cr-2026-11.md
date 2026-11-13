# Análisis competitivo — Costa Rica (noviembre 2026)

**Alcance:** únicamente productos costarricenses (o con operación y producto propio en Costa Rica).
**Objetivo:** identificar funciones que la competencia ya ofrece y Mercurius no, con prioridad y esfuerzo.
**Método:** tres pasadas de investigación independientes + verificación contra sitios primarios de cada proveedor.
**Fecha de precios:** listas públicas de noviembre 2026, sin IVA salvo que se indique.

> **Regla de alcance aplicada:** Alegra, Scrampi, Softland, Odoo, SAP B1 global y Square/Toast/Lightspeed quedan **fuera** de la matriz por ser de origen extranjero. Se listan en el Apéndice A solo para dejar constancia de que fueron descartados, no como competidores del estudio.

---

## 1. Resumen ejecutivo

Mercurius compite hoy en la intersección **inventario + POS + facturación electrónica v4.4**, y en esa intersección su backend de Hacienda es más profundo que el de la mayoría de los POS costarricenses: los siete tipos de comprobante, CABYS, multi-sucursal/terminal, firma XAdES, cola de reintento y declaración D-104 ya existen. Eso no es el problema.

El problema es que **la competencia vende paquetes cerrados por segmento**, y Mercurius vende capacidad por API. Los diez productos CR revisados coinciden en cinco frentes donde Mercurius tiene hueco:

1. **Restaurante.** Mesas, KDS, modificadores, propina/10% de servicio y recetas son *gratis* en MiPuntoVenta y estándar en PROCOM, FLK y PuraVIDA. Mercurius no tiene ninguno.
2. **Variantes y bodega lógica.** Tallas/colores/presentaciones con código de barras e inventario propio, y bodega dentro de la sucursal con traslados. Mercurius tiene sucursales y lotes, no variantes ni bodega.
3. **Contabilidad y nómina.** CoDataTech, POSMOVI, BillingCR y el canal SAP B1 (Novitec) incluyen mayor general, conciliación bancaria y planilla. Mercurius solo llega a D-104.
4. **Integración saliente.** Webhooks, tienda en línea, WhatsApp y pasarela de pagos. Mercurius tiene API entrante (Mercatus) pero no eventos salientes ni canal de venta digital.
5. **Empaquetado.** La competencia vende planes cerrados con precio público; Mercurius no publica precio ni paquete por segmento.

> **Corrección respecto a la primera versión:** el "offline" **no** es una brecha de Mercurius. El producto se instala on-premise en el local, así que una caída de internet no impide vender; solo detiene el envío a Hacienda, que ya cubre la cola con reintento. Ver §4.3.

Además hay **tres brechas de cumplimiento que no son de mercado sino de producto**, y que la competencia ya cerró: `esServicio` está fijo en `false`, la moneda está fija en CRC, y FEE/FEC/REP existen en backend sin flujo de UI.

**Lectura estratégica:** Mercurius no debe intentar ser CoDataTech ni SAP B1. Debe cerrar primero las brechas de cumplimiento (baratas, alto riesgo legal) y la de restaurante (cara pero define segmento), y dejar contabilidad/nómina como decisión explícita de "no jugamos ahí" o como alianza.

---

## 2. Conjunto competitivo verificado (10 productos CR)

| # | Producto | Casa | Segmento | Precio público | Fuente |
|---|----------|------|----------|----------------|--------|
| 1 | **Facturele** | CR (San José) | Pyme → contadores multiempresa | Básico ≈ $6.15/mes (5 docs); 12 módulos modulares | facturele.com/precios |
| 2 | **CoDataTech ERP** | CR | Pyme retail/servicios | Por planes (no publicado en USD) | erp.codatatech.com/costarica/precios |
| 3 | **POSMOVI** | CR | Retail, ferretería, súper, farmacia | Cotización | posmovi.com |
| 4 | **PROCOM / SOLARIA** | CR | Hospitalidad, retail, enterprise | Cotización | procom.cr |
| 5 | **MiPuntoVenta** (Facbox) | CR | Soda, pulpería, tienda, barbería | POS $0; Comercio $5/$13; Comida $7/$18; multi-sucursal $5; lealtad $2; variantes $3 | mipuntoventa.app/precios |
| 6 | **GTI Factura Electrónica** | CR (Curridabat) | Bajo/medio volumen, contadores | Prepago ₡13.920/24 docs; pospago ₡7.650/mes | facturaelectronica.cr |
| 7 | **Novitec** (canal SAP B1) | CR | Mediana/empresa | Cotización | novitec.cr |
| 8 | **BillingCR (HDC)** | CR | POS + ERP cloud | Desde ₡23.730/mes IVA incl. | billingcr.com |
| 9 | **QuPOS** | CR (Alajuela) | Súper, farmacia, ferretería | No publicado | qupos.com (sin respuesta) |
| 10 | **FLK POS / PuraVIDA POS** | CR | Soda, restaurante, retail | FLK ≈ $29/mes; PuraVIDA plan gratis | flkpos.com / puravida.pos (sin respuesta) |

**Confianza:** 1–8 verificados contra sitio primario en esta sesión. 9–10 provienen de investigación secundaria; su sitio no respondió (error de transporte) y **no se usan para sostener ninguna conclusión**. Se listan para completar el mapa, marcados como no verificados.

### 2.1 Perfiles

**Facturele** — el competidor más cercano en cumplimiento. v4.4 completo (FE/TE/NC/ND/FEE/FEC/REP), TRIBU-CR, contabilidad con IA y predicción de asientos, POS con mesas/comandas, PWA offline, RR.HH. con aguinaldo y CCSS, 150+ reportes, API REST **con webhooks**, conector SAP B1, multiempresa. Publica precios y tiene 12 módulos que se activan por separado. Es el referente de "hasta dónde llega el paquete".

**CoDataTech** — ERP pyme con facturación electrónica *dentro* del ERP: inventario con bodegas, traslados y conteos físicos; POS; contabilidad con asientos, períodos, estados financieros, bancos y conciliaciones; CRM con WhatsApp integrado; gestión de citas. Su diferenciador es que el POS no es un satélite: factura con cliente e inventario conectados.

**POSMOVI** — el más ancho en módulos administrativos: POS, FE, tienda en línea, inventario, contabilidad, reportes, RR.HH., control de asistencia, ruteo/venta en ruta y apps móviles. Verticalizado por tipo de negocio (súper, ropa, ferretería, tecnología, librería, juguetería, mueblería, abarrotes, calzado). Compite por cobertura, no por precio.

**PROCOM / SOLARIA** — el jugador de hospitalidad. POS con plano de mesas, múltiples cuentas por mesa, división de órdenes, KDS, kiosco/autoservicio, recetas y subrecetas, compras, BI, multi-caja y multi-establecimiento; PMS hotelero hasta 100 habitaciones; alianza con Softland. Es el techo del segmento restaurante/hotel en CR.

**MiPuntoVenta** — el competidor más peligroso por precio. POS **gratis para siempre** con mesas, inventario, caja/arqueo, CxC, opciones por producto y comandas, app móvil **con modo offline**, impresión térmica y devoluciones. Los módulos se venden sueltos: descuentos y pagos divididos $5, propinas y KDS $7, compras/stock/combos/auditoría $13, recetas e insumos $18, variantes $3, multi-sucursal $5, lealtad $2. Un restaurante completo queda en **$18–$25/mes**.

**GTI** — facturación electrónica pura, no ERP. Prepago/pospago, app móvil iOS/Android/Huawei, precarga de CABYS, respaldo 5 años, integración ERP, WhatsApp, y **Q-Declaro** como producto aparte para borradores de D-150, D-101/102 y D-116/117. Su fortaleza es el contador, no el comercio.

**Novitec** — canal SAP Business One en CR. No es un producto propio, pero define el piso de la expectativa enterprise: finanzas, logística con WMS (Produmex), manufactura (Beas), nómina (Ventura), e-commerce (Nidux), venta en ruta (Mobilvendor), POS (Retail One), IA (My Wave) y facturación v4.4 (Envoice) con aceptación masiva de documentos.

**BillingCR (HDC)** — POS + ERP cloud con precio público, POS offline con failover automático, app Android de venta, multi-caja/multi-sucursal, CABYS, encomiendas, **API REST con OAuth** y prellenado de IVA para TRIBU-CR. Es el competidor CR más parecido a Mercurius en arquitectura (nube + API), y el único que publica precio de ERP.

---

## 3. Matriz de capacidades

Leyenda: ✅ tiene · ⚠️ parcial / solo backend / no verificado · ❌ no tiene · — no aplica a su segmento.

| Capacidad | Mercurius | Facturele | CoDataTech | POSMOVI | PROCOM | MiPuntoVenta | GTI | Novitec/SAP | BillingCR |
|---|---|---|---|---|---|---|---|---|---|
| **Cumplimiento** | | | | | | | | | |
| FE/TE/NC/ND v4.4 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| FEE / FEC / REP | ⚠️ backend | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ✅ | ✅ | ✅ |
| Mensaje receptor (acepta/rechaza) | ✅ | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ✅ | ✅ | ✅ |
| CABYS | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| D-104 IVA | ✅ | ✅ | ✅ | ⚠️ | ✅ | ❌ | ✅ | ✅ | ✅ |
| D-101 / D-150 renta | ❌ | ✅ | ⚠️ | ❌ | ⚠️ | ❌ | ✅ | ✅ | ⚠️ |
| Multimoneda USD | ❌ | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ✅ | ✅ |
| Respaldo XML 5 años | ⚠️ | ✅ | ⚠️ | ⚠️ | ⚠️ | ❌ | ✅ | ✅ | ✅ |
| Contingencia / offline emisión | ⚠️ cola | ✅ | ✅ | ⚠️ | ✅ | ⚠️ | ✅ | ✅ | ✅ |
| **POS / restaurante** | | | | | | | | | |
| Mesas / plano | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ | — | ⚠️ | ❌ |
| KDS / cocina | ❌ | ⚠️ | ❌ | ❌ | ✅ | ✅ | — | ⚠️ | ❌ |
| Modificadores / opciones | ❌ | ✅ | ❌ | ⚠️ | ✅ | ✅ | — | ⚠️ | ⚠️ |
| Pago dividido | ✅ | ✅ | ⚠️ | ⚠️ | ✅ | ✅ | — | ✅ | ✅ |
| Propina / 10% servicio | ❌ | ✅ | ❌ | ❌ | ✅ | ✅ | — | ⚠️ | ❌ |
| Recetas / costo de platillo | ❌ | ⚠️ | ❌ | ❌ | ✅ | ✅ | — | ⚠️ | ❌ |
| Combos | ❌ | ⚠️ | ⚠️ | ⚠️ | ✅ | ✅ | — | ✅ | ⚠️ |
| Variantes (talla/color) | ❌ | ⚠️ | ⚠️ | ✅ | ✅ | ✅ | — | ✅ | ⚠️ |
| Venta sin internet | ✅ on-prem | ✅ | ⚠️ | ⚠️ | ✅ | ✅ | ⚠️ | ⚠️ | ✅ |
| App móvil nativa | ❌ | ⚠️ PWA | ⚠️ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| **Inventario / ERP** | | | | | | | | | |
| Multi-bodega | ⚠️ sucursal | ✅ | ✅ | ✅ | ✅ | ⚠️ | — | ✅ | ✅ |
| Traslados entre bodegas | ❌ | ✅ | ✅ | ✅ | ✅ | ❌ | — | ✅ | ✅ |
| Conteo físico | ✅ | ✅ | ✅ | ✅ | ✅ | ⚠️ | — | ✅ | ✅ |
| Series / trazabilidad | ❌ | ⚠️ | ⚠️ | ⚠️ | ✅ | ❌ | — | ✅ | ⚠️ |
| Órdenes de compra + aprobación | ⚠️ | ✅ | ✅ | ✅ | ✅ | ⚠️ | — | ✅ | ✅ |
| Mayor general / contabilidad | ❌ | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Conciliación bancaria | ❌ | ⚠️ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ✅ |
| Activos fijos | ❌ | ❌ | ⚠️ | ⚠️ | ⚠️ | ❌ | ❌ | ✅ | ⚠️ |
| Planilla / RR.HH. | ❌ | ✅ | ⚠️ | ✅ | ⚠️ | ❌ | ❌ | ✅ | ⚠️ |
| CRM / pipeline | ⚠️ clientes | ✅ | ✅ | ✅ | ✅ | ❌ | ❌ | ✅ | ⚠️ |
| Manufactura / BOM | ❌ | ⚠️ | ❌ | ❌ | ⚠️ | ❌ | ❌ | ✅ | ❌ |
| **Comercio / pagos** | | | | | | | | | |
| Tienda en línea | ❌ | ⚠️ | ❌ | ✅ | ✅ | ❌ | ❌ | ✅ | ⚠️ |
| WhatsApp commerce | ❌ | ⚠️ | ✅ | ⚠️ | ⚠️ | ❌ | ✅ | ⚠️ | ⚠️ |
| Delivery (Uber Eats/PedidosYa) | ❌ | ❌ | ❌ | ⚠️ | ⚠️ | ❌ | ❌ | ⚠️ | ❌ |
| Pasarela / datáfono integrado | ❌ | ⚠️ | ⚠️ | ⚠️ | ✅ | ⚠️ | ⚠️ | ✅ | ⚠️ |
| SINPE Móvil (código 06) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Ruteo / venta en ruta | ❌ | ❌ | ❌ | ✅ | ⚠️ | ❌ | ❌ | ✅ | ⚠️ |
| **Plataforma** | | | | | | | | | |
| API REST pública | ✅ | ✅ | ⚠️ | ⚠️ | ⚠️ | ❌ | ⚠️ | ✅ | ✅ |
| Webhooks salientes | ❌ | ✅ | ⚠️ | ❌ | ⚠️ | ❌ | ❌ | ✅ | ⚠️ |
| Asistente IA | ⚠️ pronóstico | ✅ | ⚠️ | ❌ | ⚠️ | ❌ | ❌ | ✅ | ⚠️ |
| Precio público | ❌ | ✅ | ⚠️ | ❌ | ❌ | ✅ | ✅ | ❌ | ✅ |

---

## 4. Brechas por dominio

### 4.1 Cumplimiento — tres defectos propios, no de mercado

Estas no requieren estudio competitivo: son deuda interna que la competencia ya pagó.

| Brecha | Evidencia en el repo | Riesgo | Competidores que ya lo tienen |
|---|---|---|---|
| `esServicio` fijo en `false` | `ComprobanteService.java:1135` | Todas las líneas se declaran mercancía; el resumen de servicio sale mal y Hacienda puede rechazar | Facturele, CoDataTech, BillingCR |
| Moneda fija CRC | `ComprobanteService.java:1212` (`setCodigoMoneda("CRC")`) | Un cliente que factura en USD no puede; turismo y servicios tech quedan fuera | Facturele, POSMOVI, Novitec, BillingCR |
| FEE/FEC/REP sin UI | Solo rutas/API; el flujo de emisión no existe | El usuario no puede emitir compra, exportación ni recibo de pago aunque el backend sepa | Todos los revisados |
| Exoneraciones incompletas | Solo marca `exento` | Zona franca y servicios exonerados no se documentan con tipo/número/institución | Facturele, BillingCR, Novitec |

**Esfuerzo conjunto estimado:** 18–28 días. **Impacto:** elimina riesgo legal y desbloquea venta a servicios y a clientes en dólares. Es el mejor retorno del estudio.

> Verificado: `LineaDetalle.numeroLinea` ya se asigna `i + 1` (`ComprobanteService.java:1360`), así que **no** es una brecha.

### 4.2 Restaurante — el hueco más grande y el más caro

Mercurius no tiene mesas, KDS, modificadores, propina ni recetas. MiPuntoVenta regala mesas y cobra **$7/mes** por propinas + KDS; PROCOM lo tiene como estándar enterprise; FLK y PuraVIDA lo tienen como razón de ser.

Lo relevante no es la lista, es el **precio de entrada**: un restaurante completo en MiPuntoVenta cuesta $18–$25/mes con app offline incluida. Mercurius no puede ganar ese segmento con "tenemos mejor facturación electrónica"; tiene que igualar la operación y diferenciarse en cumplimiento y multi-sucursal.

Brechas ordenadas por impacto en el flujo de un restaurante:
1. **Mesas / plano** — sin esto no hay restaurante, solo mostrador.
2. **KDS** — sin esto la comanda es papel.
3. **Modificadores** — "sin cebolla", "extra queso", tamaños.
4. **Propina / 10% de servicio** — obligación legal costarricense; FLK la aplica automática y distingue mesa vs para llevar.
5. **Pago dividido** — Mercurius ya lo tiene (`EntradaPago`), es una ventaja que no se está explotando en UI de mesa.
6. **Recetas / insumos** — costo real del platillo y descuento de inventario.
7. **Combos** — hoy solo hay promociones singulares/múltiples.

**Esfuerzo:** 25–35 días para un MVP creíble (mesas + KDS + modificadores + propina + división). Recetas y combos: +10–12 días.

### 4.3 Offline — no es una brecha de Mercurius

Mercurius se despliega **on-premise en el local del cliente**, no en la nube. El navegador habla con el servidor por la red local, así que una caída de internet **no impide vender**: POS, caja e inventario siguen funcionando. Lo único que se interrumpe es el envío a Hacienda, y para eso ya existe la cola con reintento (`EnvioFueraLinea` / `EnvioFueraLineaService`) más el modo de contingencia.

Eso invierte la comparación: **el offline es una ventaja de Mercurius frente a los POS en la nube** (MiPuntoVenta, BillingCR, Facturele), que sí dependen de internet y por eso venden "modo offline" como función. No hay que construir PWA ni IndexedDB.

Único trabajo pendiente, menor: que la UI comunique con claridad el estado de la cola (comprobantes pendientes, reintentos, contingencia) para que el cajero sepa que la venta quedó registrada aunque Hacienda no haya respondido. **Esfuerzo: 2–3 días.**

### 4.4 Inventario — variantes antes que bodegas

Verificado en código: **no existe entidad `Bodega` ni `Almacén`**. La única dimensión es `Sucursal.java`, y el inventario se lleva por artículo con lotes (`Lote.java`). Tampoco hay traslado con documento ni número de serie.

- **Variantes** (talla/color/presentación, una tarjeta en POS, precio/código/inventario por variante). MiPuntoVenta la cobra **$3/mes**; POSMOVI la tiene por vertical. Es barata de construir y abre ropa, calzado y abarrotes con presentaciones. **8–12 días.**
- **Bodega + traslados + series.** Bodega lógica dentro de una sucursal, traslado con documento y número de serie. CoDataTech y BillingCR lo tienen; es requisito de ferretería, farmacia y distribuidor. **10–15 días** para traslados y bodega; **+8** para series.

**No confundir con `DetalleSurtido`:** ese modelo ya existe (`Models/Detalles/LineaDetalleSurtido.java`) y es el concepto de Hacienda para combos de origen fabricante, no variantes comerciales.

**Conteo físico ya existe** (`inventarios` procesados/pendientes) — es una fortaleza que no se comunica.

### 4.5 Contabilidad y nómina — decisión de producto, no de roadmap

CoDataTech, POSMOVI, BillingCR y el canal SAP B1 incluyen mayor general, conciliación bancaria y planilla. Construir eso es un ERP: **40–60 días** por módulo, con riesgo de cumplimiento propio (CCSS, INS, aguinaldo, liquidaciones).

Mercurius ya tiene D-104, que es el 80% del dolor mensual del dueño de pyme. La pregunta no es "¿podemos?", es "¿queremos?".

**Tres opciones honestas:**
1. **No jugar** y posicionarse como "el POS/inventario que cumple y se integra", con exportación contable limpia. Costo: 3–5 días (export asientos/CSV).
2. **Alianza**: conectar con un contador/ERP CR vía API. Costo: 5–10 días.
3. **Construir** GL + conciliación. Costo: 40–60 días, y no se gana el segmento sin nómina.

Recomiendo **(1) + (2)**: exportación contable y webhooks. Es la única jugada que no distrae del restaurante y del offline, que sí son segmento.

### 4.6 Integración saliente — la brecha más barata de cerrar

Mercurius tiene API entrante con OAuth, scopes y rate limiting (Mercatus). No tiene **webhooks**. Facturele y BillingCR los tienen; es lo que permite que un cliente conecte su tienda, su CRM o su contador sin pedir integración a medida.

**Esfuerzo:** 3–5 días (tabla de suscripciones, firma HMAC, reintentos con backoff, log de entregas). **Impacto:** alto por dólar invertido. Habilita e-commerce, WhatsApp y contabilidad sin construir cada uno.

### 4.7 Comercio y pagos

- **Pasarela/datáfono integrado** (Tilopay, OnvoPay, BAC): los **métodos de pago ya están completos** — `Tipo_MedioPago` cubre efectivo, tarjeta, cheque, transferencia, recaudado por terceros, SINPE 06, plataforma digital y otros. La brecha no es el catálogo, es el **cobro integrado**: Mercurius registra el medio pero no habla con la pasarela ni concilia el voucher. **8–12 días** por proveedor.
- **Tienda en línea**: POSMOVI y PROCOM la tienen; construirla es 20–30 días. Con webhooks + API, un WooCommerce/Shopify se conecta sin producto nuevo.
- **WhatsApp commerce**: CoDataTech lo tiene integrado; GTI lo usa para soporte. **10–15 días** vía API de WhatsApp Cloud.
- **Delivery (Uber Eats/PedidosYa)**: ningún proveedor CR documenta integración directa. **No es brecha competitiva**, es hueco de mercado. No priorizar.

### 4.8 IA — no es table stakes todavía

Solo Facturele (asientos predichos, Facturito) y Novitec (My Wave) venden IA de forma explícita. Mercurius ya tiene un motor de pronóstico con MASE (`MotorPronostico`), que es más de lo que ofrece la mayoría.

**Recomendación:** no construir un asistente conversacional. Exponer el pronóstico existente en la UI de reposición y dejar el resto para después. **Esfuerzo:** 5–8 días para superficie de producto.

---

## 5. Roadmap priorizado

Prioridad por: riesgo legal > table stakes del segmento actual > segmento nuevo > profundidad ERP.

### P0 — Cumplimiento y venta (30–45 días)

| # | Item | Esfuerzo | Por qué |
|---|---|---|---|
| 1 | `esServicio` por línea + resumen correcto | 2–3 d | Riesgo de rechazo; bug propio |
| 2 | Multimoneda USD + tipo de cambio por comprobante | 8–12 d | Desbloquea turismo/servicios |
| 3 | UI de FEE, FEC y REP | 5–8 d | Backend listo, falta producto |
| 4 | Exoneraciones completas (tipo/número/institución/monto) | 3–5 d | Zona franca |
| 5 | Webhooks salientes (HMAC + reintentos) | 3–5 d | Habilita todo lo demás |
| 6 | Página de precios pública | 1 d | Todos los competidores directos la tienen |

**Salida:** Mercurius queda sin deuda de cumplimiento conocida y con canal de integración.

### P1 — Segmento restaurante (35–50 días)

| # | Item | Esfuerzo | Por qué |
|---|---|---|---|
| 8 | Mesas / plano + cuentas por mesa | 8–10 d | Puerta de entrada |
| 9 | KDS + comandas | 6–8 d | Operación real |
| 10 | Modificadores / opciones por producto | 5–7 d | Personalización |
| 11 | Propina y 10% servicio (mesa vs llevar) | 3–4 d | Obligación legal CR |
| 12 | División de cuenta en UI de mesa | 2–3 d | Ya existe el modelo |
| 13 | Variantes (talla/color/presentación) | 8–12 d | Barato, abre verticales |
| 14 | Estado visible de la cola Hacienda | 2–3 d | Transparencia para el cajero |

**Salida:** Mercurius puede cotizar un restaurante y una tienda de ropa sin decir "no". El plan detallado está en `docs/plans/restaurante-plan-2026-11.md`.

### P2 — Profundidad operativa (35–55 días)

| # | Item | Esfuerzo | Por qué |
|---|---|---|---|
| 16 | Multi-bodega + traslados con documento | 10–15 d | Ferretería/farmacia/distribuidor |
| 17 | Series / trazabilidad | 6–8 d | Farmacia, electrónica |
| 18 | Recetas e insumos (food cost) | 6–8 d | Restaurante Pro |
| 19 | Combos | 4–5 d | Ticket promedio |
| 20 | Pasarela de pagos (Tilopay u OnvoPay) | 8–12 d | Cobro integrado |
| 21 | Exportación contable (CSV/asientos) | 3–5 d | Alternativa a construir GL |

### P3 — Solo con decisión explícita (40–60 d cada uno)

Mayor general, conciliación bancaria, activos fijos, planilla/RR.HH., CRM con pipeline, tienda en línea propia, manufactura/BOM, asistente IA.

**No recomiendo P3 en 2027** salvo que la estrategia sea competir con CoDataTech/SAP B1. Cada uno es un producto, no una función.

---

## 6. Dónde Mercurius ya gana (no perder esto)

- **On-premise y sin dependencia de internet**: el POS, la caja y el inventario funcionan en la red local aunque se caiga el enlace. Los competidores cloud venden "modo offline" precisamente porque no lo tienen. Es un argumento de venta, no un pendiente.
- **Profundidad Hacienda v4.4**: siete tipos, XAdES, CABYS, multi-sucursal/terminal, cola de reintento, D-104. Ningún POS CR revisado muestra este nivel en su web.
- **Seguridad de plataforma**: JWT con `iss`/`aud`/`type` separados, rate limiting por cuenta y origen, igualación de tiempo contra enumeración, `XmlSeguro` sin DTD, rutas de adjuntos canónicas. Esto no lo comunica ningún competidor.
- **Integridad de inventario**: bloqueo optimista, lotes, conteo físico.
- **Pronóstico**: motor con MASE y selección de método, ya construido.
- **Reportes programados por correo**: implementado; la mayoría lo tiene solo en planes altos.
- **Precio**: si se publica, Mercurius puede competir muy por debajo de BillingCR (₡23.730/mes) y de Facturele modular.

**La historia de venta correcta** no es "somos más baratos que SAP". Es: *"el único POS costarricense que cumple v4.4 de punta a punta, vende sin internet y no te obliga a comprar un ERP"* — pero para decir eso hay que cerrar P0 y P1 primero.

---

## 7. Apéndice A — Descartados por alcance

Productos de origen extranjero con operación en CR. Se investigaron y **no cuentan** para este estudio ni para la matriz:

- **Alegra** (Colombia) — POS offline, FE 4.4, restobar, MCP IA. Precio POS desde $15/mes.
- **Scrampi** (Colombia) — offline-first, KDS, recetas, split; FE CR "en evaluación". $13/$25/$42.
- **Softland** (España/LATAM) — ERP, nómina, CRM, e-commerce. Cotización.
- **Odoo / SAP B1** (global) — vía integradores; no producto CR propio.
- **Square, Toast, Lightspeed, Shopify, Clover, Loyverse, Zoho** — sin operación CR relevante; descartados.

**Nota:** Scrampi no debe citarse como competidor con facturación CR hasta que publique homologación; su propio sitio dice "en evaluación".

## 8. Apéndice B — No verificados

- **QuPOS**, **FLK POS**, **PuraVIDA POS**: sitios sin respuesta durante la sesión. Aparecen en el mapa por investigación secundaria; no sostienen conclusiones.
- **Siges, DataPro, Exactus, Contabilium, Biller, Siprom**: no son productos de facturación costarricenses (Siges/DataPro/Exactus resuelven a empresas de otros rubros o países; Contabilium y Biller son AR/UY). Descartados.
- Precios de CoDataTech, POSMOVI, PROCOM, Novitec: no públicos; "cotización".

## 9. Fuentes

- Facturele — https://www.facturele.com/ · https://www.facturele.com/precios/
- CoDataTech — https://erp.codatatech.com/costarica/factura-electronica-costa-rica/ · https://erp.codatatech.com/costarica/precios/
- POSMOVI — https://posmovi.com/
- PROCOM — https://www.procom.cr/
- MiPuntoVenta — https://mipuntoventa.app/punto-de-venta/costa-rica · https://mipuntoventa.app/precios
- GTI — https://www.facturaelectronica.cr/
- Novitec — https://www.novitec.cr/solucion/envoice/ · https://www.novitec.cr/solucion/sap-business-one/
- BillingCR — https://www.billingcr.com/
- Hacienda v4.4 / TRIBU-CR — resoluciones MH-DGT-RES-0027-2024; cambios v4.4 v2 vigentes 1 nov 2026
- Repo Mercurius — `README.md`, `docs/plans/comprobantes-hacienda-audit.md`, `ComprobanteService.java`, `templates/pages/tributacion/`
