import Base

# F1: aritmética del D-104 con los totales reales de 2026.
# Montos en céntimos, en U32: los totales de cada mes caben en 2^32.
# (Nat es unario en Bend 2: con miles de millones desborda la pila; por eso U32.)
# El acumulado del año (18.972.146.504) no cabe en U32 y por eso queda fuera.

# --- Política de IVA -------------------------------------------------------
# El IVA se reconstruye por línea al 13 %: 13 de cada 113 del precio final.
def iva_de(base: Nat) -> Nat:
  Nat.div(Nat.mul(base, 13n), 113n)

# LEY: 13 de cada 113 es exactamente 13.
law iva_113:
  {iva_de(113n) == 13n : Nat}

# --- Casos de oro: neta + IVA = total, por mes -----------------------------
# Datos tomados de la base el 2026-11-17, en céntimos.

law enero_cuadra:
  {((2382144277 + 280378858) : U32) == 2662523135 : U32}

law febrero_cuadra:
  {((1819370525 + 218594421) : U32) == 2037964946 : U32}

law marzo_cuadra:
  {((2707207231 + 322097212) : U32) == 3029304443 : U32}

law abril_cuadra:
  {((2659936701 + 321233324) : U32) == 2981170025 : U32}

law mayo_cuadra:
  {((1772111880 + 213715895) : U32) == 1985827775 : U32}

law junio_cuadra:
  {((1622770197 + 194312078) : U32) == 1817082275 : U32}

law julio_cuadra:
  {((1937487199 + 234930586) : U32) == 2172417785 : U32}

law agosto_cuadra:
  {((2043684709 + 242171411) : U32) == 2285856120 : U32}
