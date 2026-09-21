-- Move AUTH_SESSION_KEY from env to DB: add column for DB-managed session encryption key
ALTER TABLE appsettings ADD COLUMN IF NOT EXISTS auth_session_key VARCHAR(64);
-- hacienda_encryption_key already exists since V2026_08_18, no change needed
-- Existing %prod env overrides for both keys are now removed from application.properties;
-- keys are auto-generated via AppSettingsService/DbConfigSource on first boot if missing
