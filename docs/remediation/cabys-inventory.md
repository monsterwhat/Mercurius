# Inventario CABYS para la decisión fiscal 01 / 10 / 11

Fecha: 2026-11-22
Estado: **hoja de trabajo pendiente de clasificación fiscal**.
No se modificó producción.

## 1. Por qué no basta `impuesto = 0`

El catálogo CABYS trae una tasa, no una calificación legal. Una tasa 0 puede
corresponder a:

| Código | Uso legal | Consecuencia |
|---|---|---|
| `01` | Tarifa 0% del Artículo 32(1) RLIVA | Sí otorga crédito fiscal pleno; aplica a casos calificados (p. ej. CCSS, municipalidades). |
| `10` | Tarifa Exenta, Ley 9635 Artículo 8 | Exención legal; no se deriva de una tasa 0 genérica. |
| `11` | Tarifa 0% sin derecho a crédito | Operación no sujeta que no otorga crédito. |

El valor `impuesto = 0` del CABYS no distingue esos tres casos.

## 2. Inventario local (solo referencia, NO es producción)

Consultas ejecutadas contra las bases locales accesibles:

```text
mercurius (5433)
  artículos: 252008
  artículos con cabys_id: 1
  artículos con CABYS impuesto 0/nulo: 0

mercurius_test (5433)
  artículos: 1 (fixture de prueba)
  artículos con CABYS impuesto 0: 1 ("Articulo de prueba envio")
```

Distribución del catálogo CABYS local:

```text
impuesto 13: 91316
impuesto 4:   8625
impuesto 2:   7932
impuesto 8:   5952
impuesto 1:   3835
impuesto 0:   2845
```

Conclusión: la base local no contiene el catálogo real de artículos vinculados.
No se puede decidir con estos datos.

## 3. Consulta para producción

Ejecutar `docs/remediation/cabys-inventory.sql` contra producción o una copia
restaurada. Devuelve una fila por CABYS afectado con:

- código y descripción CABYS;
- impuesto CABYS;
- artículos activos/totales;
- nombres de ejemplo;
- columnas vacías para `legal_basis`, `selected_code`, `confirmed_by`,
  `confirmed_at` y `notes`.

## 4. Hoja de decisión

Una vez ejecutada la consulta, completar:

| cabys_codigo | descripcion | articulos_activos | legal_basis | selected_code | confirmed_by | confirmed_at |
|---|---|---:|---|---|---|---|
| (pendiente) | (pendiente) | | | `01` / `10` / `11` | | |

Reglas:

- Si el producto está legalmente exento por Ley 9635 Art. 8: `10`.
- Si es no sujeto y no otorga crédito fiscal: `11`.
- Si califica para el tratamiento del Art. 32(1): `01`.
- Si el catálogo mezcla casos, la clasificación debe ser por CABYS, no global.
- Un CABYS sin base legal confirmada no se emite con una suposición.

## 5. Implementación posterior (bloqueada)

Cuando la hoja esté confirmada:

1. Usar el `selected_code` por CABYS (no un mapeo global `0 -> 10`).
2. Emitir un `Impuesto` cero con `CodigoTarifaIVA` confirmado, `Tarifa = 0`,
   `Monto = 0`.
3. Mantener intactas las líneas gravadas.
4. Corregir el comportamiento de cola asíncrona expuesto por el experimento.
5. Añadir la matriz de pruebas fiscales.
6. Ejecutar `mvn -f app/pom.xml clean test` y exigir `Failures: 0`,
   `Errors: 0`.

La decisión aplicada y la evidencia oficial están en
`docs/remediation/fiscal-blocker.md`. El inventario de producción está en
`docs/remediation/cabys-inventory-prod.md`.
