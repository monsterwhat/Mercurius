# spec/ — leyes verificadas con Bend

Especificación ejecutable de las reglas de negocio puras de Mercurius, escrita
en [Bend 2](https://bend-lang.com). **No entra en runtime**: el sistema sigue
siendo Java. Esto es un detector de divergencias y un gate de pruebas.

Ver el plan completo en [`docs/plans/bend-integracion-plan.md`](../docs/plans/bend-integracion-plan.md).

```
spec/
  laws/LAWS.bend            F0: modelo mínimo de comprobante
  laws/PROOF.bend
  laws/falsa/               ley falsa a propósito (demuestra el rechazo)
  laws/d104/LAWS.bend       F1: política de IVA + totales reales de 2026
  laws/d104/PROOF.bend
```

## F0 — mecanismo (completado)

Modelo mínimo (`Comprobante{base, iva}` en céntimos) y tres leyes:

| Ley | Enunciado |
|---|---|
| `iva_cero` | sin base no hay IVA |
| `iva_de_113` | 13 de cada 113 es exactamente 13 |
| `total_de_cero` | una línea en cero totaliza cero |

```
$ bend PROOF.bend
ALL PROOFS CHECK
```

Con una ley falsa (`laws/falsa/`) el gate se cierra:

```
$ bend PROOF.bend
SOME PROOFS FAIL
- expected : 13n
- observed : 14n
Location: Laws.iva_de_113_falsa
```

## F1 — D-104 y totales reales (completado)

Nueve leyes en `laws/d104/`:

- política de IVA: `iva_de(base) = base × 13 / 113`, con `iva_113` como ancla;
- **casos de oro**: `neta + IVA = total` para enero–agosto 2026, con los montos
  reales de la base en céntimos.

```
$ bend PROOF.bend
ALL PROOFS CHECK
```

Y si se cambia un solo céntimo en marzo:

```
$ bend PROOF.bend
SOME PROOFS FAIL
- expected : 3029304444
- observed : 3029304443
Location: Laws.marzo_cuadra
```

Es decir: el gate detecta **un céntimo** de diferencia contra los datos reales.

## Cómo correrlo

```bash
# instalación (Linux/macOS; necesita clang 14+, 19+ para paralelismo)
curl -fsSL https://bend-lang.com/install.sh | sh
export PATH="$HOME/.bend/bin:$PATH"

cd spec/laws      && bend PROOF.bend   # F0
cd spec/laws/d104 && bend PROOF.bend   # F1
```

En el servidor el piloto vive en `/opt/bend-pilot` (mismos archivos).

## Convención

- `LAWS.bend` lo escribe la persona: son las reglas del negocio.
- `PROOF.bend` lo escribe quien implementa (humano o IA).
- Si una ley se rompe, **no hay build**. No se "ajusta la ley para que pase".

## Limitaciones encontradas

1. **`Nat` es unario**: sumar miles de millones desborda la pila
   (“the machine stack overflowed”). Los montos grandes van en `U32`.
2. **Los literales de `Nat` llegan a 4294967295n**; lo que pasa de ahí hay que
   armarlo con `Nat.mul` + `Nat.add`.
3. **`U32` llega a 4.294.967.295**: los totales mensuales en céntimos caben, el
   acumulado del año (18.972.146.504) no. Por eso la ley anual quedó fuera.
4. **Los operadores exigen anotación**: `(a + b : U32)`, no `a + b`.
5. **Todo va anotado** y `def` exige paréntesis aunque no tenga parámetros.
6. **Los campos se sacan por pattern matching**, no con `c.campo`.
7. **Import por ruta**: `import ./LAWS.bend as Laws`, y la prueba se llama
   `def Laws.<ley>`.
8. **Sin tácticas**: `{==}` solo prueba lo que calcula al mismo término; lo que
   no, hay que inducirlo. La ley “el IVA nunca supera la base” para todo `base`
   necesita inducción y quedó pendiente.
9. **`--verdict` requiere Lean 4.34** (elan) o `$BENDTT`. Sin eso, el check
   normal funciona igual.

## Siguiente (F2)

- Contrato de importación: idempotencia y clave natural (el error de febrero y
  de 2024).
- Ley de redondeo: `sum(round(línea))` vs `round(sum(línea))`, con la política
  explícita y su discrepancia acotada.
