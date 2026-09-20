# Database Migrations

> Stub — manual migration process. No automated migration tool (Flyway/Liquibase) is wired yet. Hibernate manages DDL per profile; production validates only.

## Strategies per profile

| Profile | `quarkus.hibernate-orm.schema-management.strategy` | DDL behavior |
|---------|---------------------------------------------------|--------------|
| `%dev` (default) | `update` (global at `application.properties:15`) | Hibernate alters schema in place on boot. Ergonomic for local development. |
| `%test` | `drop-and-create` (`application.properties:135`) + `import-test.sql` | Fresh schema + seed data per test JVM (`mercurius_test` on `localhost:5433`). |
| `%prod` | `validate` (`%prod` override at `application.properties:192`) | Hibernate **only validates** entities vs. existing DDL; never mutates prod. Mismatch = boot failure. |

`%prod` also requires env vars with no defaults (`DB_USERNAME`, `DB_PASSWORD`, `DB_URL`, `MERCATUS_JWT_SECRET`, `MERCATUS_CORS_ORIGINS`) — app fails fast if missing. DB-managed keys (`AppSettings.authSessionKey`, `AppSettings.haciendaEncryptionKey`) are auto-generated on first boot and not part of `%prod` hardening.

App still boots unchanged in dev/test — only `%prod` is affected.

## Current baseline

**Date:** 2026-09-19 — `src/main/resources/application.properties` baseline, `mercurius-quarkus` 2.4.0 (Quarkus 3.36.2, PostgreSQL 14+ / verified 18, `quarkus-jdbc-postgresql`).

**Source of truth:** 71 `@Entity` classes under `src/main/java/Models/**` (Hibernate + Panache). Schema is whatever `update`/`drop-and-create` generates from these entities at this commit. No `import.sql` for dev; test uses `src/test/resources/import-test.sql`.

**Entity inventory (for diff baseline):**

- Core: `Models.Users`, `Models.Clients`, `Models.ClienteActividad`, `Models.ApiClients`, `Models.AppSettings`, `Models.ConsecutivoReceptor`, `Models.ConsecutivoEmitido`, `Models.Cabys`, `Models.TipoCambio`, `Models.CierreCaja`, `Models.Lote`, `Models.Inventario`, `Models.Departamento`, `Models.Familia`, `Models.DepartamentoMetrico`, `Models.ConfiguracionMargen`, `Models.ProfitMarginHistory`, `Models.ProfitMarginSnapshot`, `Models.ReorderSuggestion`, `Models.PuntosTransaccion`, `Models.StockAlert`, `Models.UserShortcut`
- Articulos: `Models.Articulos.Articulos`, `Models.Articulos.ArticuloPrecio`, `Models.Articulos.ArticuloStock`, `Models.Articulos.ArticuloImagen`, `Models.Articulos.Promocion`, `Models.Articulos.Carrito.ArticuloCarrito`
- Marketplace: `Models.Marketplace.MarketplaceCartItem`, `Models.Marketplace.MarketplaceOrder`, `Models.Marketplace.MarketplaceOrderItem`
- Comprobantes: `Models.ComprobantesEmitidos`, `Models.ComprobantesRecibidos`, `Models.NotaCredito`, `Models.OrdenCompra`, `Models.OrdenCompraDetalle`, `Models.ProductoExoneracion`
- Correos: `Models.Correos.EmailTemplate`, `Models.Correos.ReporteProgramado`
- Validacion: `Models.Validacion.PrevalidationConfig`
- Encabezado: `Models.Encabezado.Encabezado`, `Models.Encabezado.Emisor`, `Models.Encabezado.Receptor`, `Models.Encabezado.Ubicacion`, `Models.Encabezado.Telefono`, `Models.Encabezado.Fax`, `Models.Encabezado.CorreoElectronicoEmisor`, `Models.Encabezado.CorreoElectronicoReceptor`, `Models.Encabezado.MedioPago`, `Models.Encabezado.IdentificacionEmisor`, `Models.Encabezado.IdentificacionReceptor`
- Detalles: `Models.Detalles.DetalleServicio`, `Models.Detalles.LineaDetalle`, `Models.Detalles.LineaDetalleSurtido`, `Models.Detalles.Impuesto`, `Models.Detalles.ImpuestoSurtido`, `Models.Detalles.Descuento`, `Models.Detalles.DescuentoSurtido`, `Models.Detalles.OtroCargo`, `Models.Detalles.CodigoComercial`, `Models.Detalles.CodigoComercialSurtido`, `Models.Detalles.Exoneracion`, `Models.Detalles.IdentificacionTercero`, `Models.Detalles.DatosImpuestoEspecifico`, `Models.Detalles.DatosImpuestoEspecificoSurtido`, `Models.Detalles.NumeroVINoSerie`
- Resumen: `Models.Resumen.ResumenFactura`, `Models.Resumen.MedioPagoR`, `Models.Resumen.TotalDesgloseImpuesto`
- Referencias: `Models.Referencias.InformacionReferencia`

> To capture a reproducible DDL snapshot: run dev once (`mvn quarkus:dev` or `.\run-dev.bat`) against `mercurius` on `localhost:5433`, then `pg_dump --schema-only mercurius > docs/migrations/baseline-schema.sql`. Commit that dump as `V0__baseline.sql` when introducing a migration tool.

## Manual migration process (until tooling is added)

1. **Change entities** in `src/main/java/Models/**` as needed (no DDL hand-edits in dev).
2. **Boot in dev** — `update` applies changes to local `mercurius` DB. Verify with `mvn test` (uses `drop-and-create` against `mercurius_test`) and manual QA at `http://localhost:8081/Mercurius`.
3. **Diff DDL** — compare prod DDL vs. new entities:
   ```bash
   pg_dump --schema-only mercurius > /tmp/new.sql
   diff -u docs/migrations/baseline-schema.sql /tmp/new.sql
   # or use Hibernate export: mvn quarkus:dev -Dquarkus.hibernate-orm.schema-management.strategy=validate
   # and inspect validation errors on boot
   ```
4. **Write idempotent SQL** — create `docs/migrations/V<NNN>__<description>.sql` (e.g., `V001__add_clientes_loyalty_column.sql`). Must be rerunnable-safe where possible (`ADD COLUMN IF NOT EXISTS`, etc.) and include `ROLLBACK` notes in file header comments.
5. **Apply to prod manually** (psql or admin tool), in order:
   ```bash
   psql "$DB_URL" -f docs/migrations/V001__add_clientes_loyalty_column.sql
   ```
6. **Validate prod boot** — deploy with `%prod` (`quarkus.hibernate-orm.schema-management.strategy=validate`). Hibernate will fail fast if migration is incomplete/incorrect. Check `logs/mercurius.log`.
7. **Update baseline** — after successful prod migration, refresh `docs/migrations/baseline-schema.sql` via `pg_dump --schema-only` and commit.

### Naming & ordering

- `V<NNN>__<snake_case_description>.sql` — zero-padded `NNN` (`001`, `002` …), double underscore separator.
- Never edit a shipped `V*.sql` after it has been applied to prod — add a new `V<NNN+1>`.
- Keep each migration focused (one logical change). Include header: `-- V001: add X — Author — Date — Jira/Issue`.

### Future tooling

When Flyway/Liquibase is introduced, `baseline-schema.sql` becomes `V0__baseline.sql` and `%prod` will switch from `validate` to tool-managed migrations (Hibernate stays `validate`). No Java changes required for this stub.
