-- 2026-11-13
-- Modo de transmision a Hacienda configurable desde Ajustes.
--   simulada -> arma/valida/firma/imprime el comprobante, sin llamada HTTP
--   real     -> transmite al ambiente configurado (sandbox o produccion)
--   null     -> decide el perfil: dev/test simula, produccion transmite
ALTER TABLE appsettings
    ADD COLUMN IF NOT EXISTS modohacienda VARCHAR(20);

-- Sin UPDATE: el valor lo fija el administrador desde Ajustes. En desarrollo
-- DevSettingsSeeder lo deja en 'simulada' la primera vez.
