import ./LAWS.bend as Laws

# Intento de prueba de una ley falsa: el checker debe rechazarla.
# Esperado: SOME PROOFS FAIL, con expected 13n / observed 14n.
def Laws.iva_de_113_falsa():
  {==}
