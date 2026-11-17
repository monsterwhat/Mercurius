import Base

# Modelo mínimo de un comprobante para el piloto de Bend.
# Todo en céntimos (Nat), sin flotantes: el dinero no se prueba con F32.

type Comprobante is Data:
  Comprobante{base: Nat, iva: Nat}

# IVA al 13 % sobre la base: 13 de cada 113 del precio final.
def iva_de(base: Nat) -> Nat:
  Nat.div(Nat.mul(base, 13n), 113n)

# Total de una línea: base + IVA.
def total_de(c: Comprobante) -> Nat:
  match c:
    case Comprobante{+base, +iva}:
      Nat.add(base, iva)

# LEY 1: sin base no hay IVA.
law iva_cero:
  {iva_de(0n) == 0n : Nat}

# LEY 2: 13 de cada 113 es exactamente 13.
law iva_de_113:
  {iva_de(113n) == 13n : Nat}

# LEY 3: una línea en cero totaliza cero.
law total_de_cero:
  {total_de(Comprobante{0n, 0n}) == 0n : Nat}
