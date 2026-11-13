-- 2026-11-13
-- `ultimo_error` deja de mapearse como @Lob y pasa a VARCHAR(2000).
-- La pantalla de la cola de envío proyecta esta columna; leerla como LOB
-- fuera de una transacción falla con "Unable to access lob stream".
-- El servicio ya trunca el mensaje a 2000 caracteres (ERROR_MAXIMO).
ALTER TABLE envio_fuera_linea
    ALTER COLUMN ultimo_error TYPE varchar(2000)
    USING left(ultimo_error::text, 2000);
