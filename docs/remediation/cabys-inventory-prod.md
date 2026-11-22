# Inventario CABYS de producción — decisión 01 / 10 / 11

Fecha: 2026-11-22
Fuente: PostgreSQL `mercurius` en `192.168.1.72` (consulta de solo lectura).
Consulta: `docs/remediation/cabys-inventory.sql`.
Hoja de trabajo exportada: `docs/remediation/cabys-afectados-produccion.csv`.

## Verificación del universo

La base de producción sí contiene un catálogo grande; el filtro de la consulta
es lo que reduce el resultado:

```text
artículos totales:                 23,228
artículos activos:                  9,397
artículos con CABYS:                8,123
CABYS distintos usados:               647
CABYS usados sin match:                 0
```

Distribución de los 9,397 artículos activos por tasa CABYS:

| Tasa | Artículos activos |
|---|---:|
| 13% | 5,607 |
| Sin CABYS | 2,585 |
| 1% | 1,003 |
| 2% | 110 |
| 0% | 92 |

Por eso el inventario de tasa 0 son **92 artículos en 11 CABYS**, no todo el
catálogo. La mayoría de los productos está gravada a 13%, 1% o 2%.

Hallazgo adicional: **2,585 artículos activos no tienen CABYS vinculado**.
Eso es un problema de datos independiente del bloqueo fiscal: un artículo sin
CABYS no puede construir una línea fiscal válida y debe corregirse antes de
venderlo.

## Resultado

**11 CABYS afectados** con `impuesto = 0` y al menos un artículo vinculado.
No es un problema de las 889 filas CABYS con tasa 0: solo estas están en uso.

| CABYS | Descripción | Artículos activos | Artículos totales | Ejemplos |
|---|---|---:|---:|---|
| `2502000000200` | Cigarrillos que contengan tabaco | 49 | 57 | Marlboro, L&M, Pall Mall, Viceroy, Seneca |
| `3627001010000` | Preservativos, de caucho vulcanizado sin endurecer | 17 | 19 | Durex, Skyn, Rider |
| `2502000000100` | Cigarros (puros), incluso despuntados y cigarritos, que contengan tabaco | 14 | 20 | Cohiba, Guantanamera, Partagás, picadoras, vapeadores |
| `2686000000100` | Gasa de algodón | 3 | 3 | Espadadrapo Hansaplast, gasa Chasal |
| `3569107009900` | Curitas, apósitos y demás artículos con capa adhesiva, n.c.p. | 3 | 5 | Curefast, Hansaplast, Transpiel |
| `3569199000200` | Aplicadores antisépticos con motas de algodón en uno o ambos extremos | 3 | 3 | Aplicador bambú, madera, plástico |
| `3336001000200` | Diésel con densidad máxima de 0.87 kg/litro | 1 | 1 | **GLAD PLASTICO ADHESIVO 100P** (dato inconsistente) |
| `3544099990100` | Prueba de embarazo | 1 | 1 | Prueba de embarazo Acierto |
| `3569106009900` | Guatas, gasas, vendas y apósitos médicos impregnados, n.c.p. | 1 | 1 | Magasa 45g |
| `3221000000000` | Libros o textos escolares, impresos | 0 | 1 | — |
| `3569106000200` | Algodón quirúrgico (incluye "swabs") | 0 | 4 | — |

## Observaciones de datos

1. **`3336001000200` (diésel)** está asignado a un artículo de plástico
   adhesivo. Es un error de catálogo, no una decisión fiscal. Corregir el CABYS
   del artículo antes de clasificar.
2. **Cigarrillos y cigarros** pueden estar sujetos a impuestos específicos, no
   solo IVA. La tasa `0` del CABYS no implica exención de Ley 9635.
3. **Preservativos, gasas, curitas, aplicadores, algodón y pruebas de embarazo**
   son candidatos a bienes médicos; la base legal debe confirmarse, no
   asumirse.
4. **Libros escolares** tienen una base legal distinta a la de suministros
   médicos.
5. `3221000000000` y `3569106000200` tienen artículos pero ninguno activo.
   Clasificarlos solo si se reactivan o si el responsable fiscal lo exige.

## Hoja de decisión

| CABYS | legal_basis | selected_code | confirmed_by | confirmed_at | notes |
|---|---|---|---|---|---|
| `2502000000200` | | | | | |
| `3627001010000` | | | | | |
| `2502000000100` | | | | | |
| `2686000000100` | | | | | |
| `3569107009900` | | | | | |
| `3569199000200` | | | | | |
| `3336001000200` | | | | | Corregir CABYS del artículo primero |
| `3544099990100` | | | | | |
| `3569106009900` | | | | | |
| `3221000000000` | | | | | Sin artículos activos |
| `3569106000200` | | | | | Sin artículos activos |

## Criterio aplicado

Hacienda v4.4 (Anexo, nota 8.1 y descripción de
`TotalServExentos`/`TotalMercanciasExentas`) asocia los CAByS exentos de IVA
con el código `10` (Tarifa Exenta). El código `01` queda reservado para la
tarifa 0% del Art. 32.1 RLIVA y el `11` para operaciones no sujetas sin derecho
a crédito.

Por eso la implementación emite `10` para toda tasa CABYS `0`/vacía, con
`Tarifa = 0` y `Monto = 0`. No se inventó un mapeo por código: la clasificación
legal que el CABYS no aporta se concentra en un único punto
(`Tipo_TarifaIVA.getTarifa`).

Si el responsable fiscal clasifica alguno de estos CABYS como **no sujeto**
(sin derecho a crédito), esa línea debe emitir `11`. La tabla de la sección
anterior es el registro de esa posible corrección por código.

## Estado

- Producción: sin cambios desplegados; el cambio de código está en el
  repositorio.
- Repositorio: **regression-clean** (`mvn clean test`: 1572 tests, 0 fallos,
  0 errores, 1 skipped).
- Detalle técnico y evidencia oficial: `docs/remediation/fiscal-blocker.md`.
