-- 2026-11-16
-- Idempotencia de la API pública de ventas (POST /api/v1/invoicing/sales).
-- Hibernate crea la tabla en dev/prod por schema-management=update; este
-- script queda para instalaciones con migración manual.
CREATE TABLE IF NOT EXISTS api_venta_idempotencia (
    id              BIGSERIAL PRIMARY KEY,
    api_client_id   VARCHAR(120) NOT NULL,
    clave           VARCHAR(120) NOT NULL,
    comprobante_id  BIGINT NULL,
    creado_en       TIMESTAMP NOT NULL DEFAULT now(),
    actualizado_en  TIMESTAMP NULL,
    CONSTRAINT uq_api_venta_idem UNIQUE (api_client_id, clave)
);

CREATE INDEX IF NOT EXISTS idx_api_venta_idem_cliente
    ON api_venta_idempotencia (api_client_id);
