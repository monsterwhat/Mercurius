-- ============================================================================
-- import-test.sql — T2 seed for @QuarkusTest runs (%test profile).
--
-- Executed by Hibernate AFTER schema drop-and-create against the local
-- PostgreSQL instance (localhost:5433/mercurius_test), so plain INSERTs are
-- duplicate-safe: every test boot starts from an empty, freshly generated
-- schema.
--
-- Physical names mirror the entity mappings exactly (Quarkus default physical
-- naming strategy keeps annotated/property names verbatim — verified
-- empirically against information_schema after boot):
--   Models/Users.java         @Table(name="Users"): username has a UNIQUE
--                             constraint; columns username, password,
--                             groupName, status, email
--   Models/Departamento.java  table "Departamento": fecha is
--                             @Column(nullable=false) and raw SQL bypasses
--                             @PrePersist, so it must be supplied here
--   Models/Familia.java       table "Familia": fecha NOT NULL (same reason)
--   Models/Clients.java       @Table(name="Clients"): primitive fields
--                             discount (double), taxpayer (boolean),
--                             zoneCode (int) are NOT NULL; nullable columns
--                             are omitted
--
-- Users.password is a REAL cost-12 BCrypt hash ($2a$..., 60 chars) of the
-- password 'admin123', generated with at.favre.lib.crypto.bcrypt 0.10.2 via
-- jshell (BCrypt.withDefaults().hashToString(12, ...)) — the same library and
-- cost used by Services/LoginService.hashPassword()/verifyPassword().
-- Provenance transcript: .omo/evidence/t14/bcrypt-jshell-transcript.txt
-- Seed credentials for auth tests (T14/T15): admin / admin123
-- ============================================================================

INSERT INTO Users (username, password, groupName, status, email)
VALUES ('admin', '$2a$12$eWh/rpjtFuusW6z3Z43gwOMFuu10/eXnnj9V4D05WqkarC86uGkuu', 'admin', TRUE, 'admin@mercurius.local');

INSERT INTO Departamento (nombre, status, fecha)
VALUES ('Departamento General', TRUE, CURRENT_TIMESTAMP);

INSERT INTO Familia (nombre, status, fecha)
VALUES ('Familia General', TRUE, CURRENT_TIMESTAMP);

INSERT INTO Clients (name, email, idType, idNumber, discount, phoneNumber, taxpayer, zoneCode, TipoIdentificacion, status)
VALUES ('Cliente Contado', 'cliente@mercurius.local', 'Cedula Fisica', '000000000', 0.0, '8888-8888', FALSE, 0, '01', TRUE);
