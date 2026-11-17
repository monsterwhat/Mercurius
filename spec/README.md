# spec/ — leyes verificadas con Bend

Especificación ejecutable de las reglas de negocio puras de Mercurius, escrita
en [Bend 2](https://bend-lang.com). **No entra en runtime**: el sistema sigue
siendo Java. Esto es un detector de divergencias y un gate de pruebas.

Ver el plan completo en [`docs/plans/bend-integracion-plan.md`](../docs/plans/bend-integracion-plan.md).

## Estado: F0 completado

Piloto con tres leyes sobre un modelo mínimo de comprobante (céntimos, `Nat`,
sin flotantes):

| Ley | Enunciado |
|---|---|
| `iva_cero` | sin base no hay IVA |
| `iva_de_113` | 13 de cada 113 es exactamente 13 |
| `total_de_cero` | una línea en cero totaliza cero |

Resultado en `.72`:

```
$ bend PROOF.bend
ALL PROOFS CHECK
Use --verdict for mathematical validity.
```

Y con una ley falsa a propósito (`labs/falsa/`):

```
$ bend PROOF.bend
SOME PROOFS FAIL
Error:
- expected : 13n
- observed : 14n
Location: Laws.iva_de_113_falsa
```

O sea: el gate **acepta lo correcto y rechaza lo incorrecto**.

## Cómo correrlo

```bash
# instalación (Linux/macOS; necesita clang)
curl -fsSL https://bend-lang.com/install.sh | sh
export PATH="$HOME/.bend/bin:$PATH"

cd spec/laws
bend PROOF.bend          # gate
bend PROOF.bend --verdict  # kernel verificado en Lean (requiere Lean 4.34)
```

En el servidor el piloto vive en `/opt/bend-pilot` (mismos archivos que
`spec/laws`).

## Convención

- `LAWS.bend` lo escribe la persona: son las reglas del negocio.
- `PROOF.bend` lo escribe quien implementa (humano o IA).
- Si una ley se rompe, **no hay build**. No se "ajusta la ley para que pase".

## Limitaciones encontradas en F0

1. Todo va anotado: no hay inferencia, y `def` exige paréntesis aunque no tenga
   parámetros (`def f():`).
2. Los campos de un registro se sacan por pattern matching, no con `c.campo`.
3. Los imports son por ruta: `import ./LAWS.bend as Laws`, y la prueba se llama
   `def Laws.<ley>`.
4. `--verdict` necesita Lean 4.34 (toolchain de elan) o `$BENDTT` apuntando a un
   kernel compilado. Sin eso, el check normal funciona igual.
5. No hay tácticas: una igualdad se prueba con `{==}` sólo si ambos lados
   calculan al mismo término. Lo que no calcula, hay que inducir.

## Siguiente (F1)

- Aritmética del D-104: base gravada, exenta, IVA y neto.
- Política de redondeo explícita (por línea vs por factura) y su prueba.
- Casos de oro contra los totales reales de 2026, que ya cuadran al centavo.
