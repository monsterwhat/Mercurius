-- Migration: Add structured location fields to Clients for Receptor Ubicacion
-- Without these, electronic invoice Receptor section lacks provincia/canton/distrito

ALTER TABLE clients ADD COLUMN provincia VARCHAR(1) NULL;
ALTER TABLE clients ADD COLUMN canton VARCHAR(2) NULL;
ALTER TABLE clients ADD COLUMN distrito VARCHAR(2) NULL;
