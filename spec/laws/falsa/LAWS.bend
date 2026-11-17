import Base

def iva_de(base: Nat) -> Nat:
  Nat.div(Nat.mul(base, 13n), 113n)

# LEY FALSA a propósito: el 13 % de 113 es 13, no 14.
# Sirve para comprobar que el checker rechaza una ley que no se cumple.
law iva_de_113_falsa:
  {iva_de(113n) == 14n : Nat}
