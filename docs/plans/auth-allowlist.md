# Authorization allowlist (P2 Phase 0.2)

Every class in `app/src/main/java` with a class-level `@Path` must either carry
`@RolesAllowed`, `@Authenticated` or `@PermitAll`, or be listed here **with a
reason**. `Controllers/AppAuth/AuthorizationCoverageTest` enforces both
directions: an unlisted ungated resource fails, and a listed entry that is
missing or has since been annotated also fails (so this file cannot rot).

When you annotate one of these classes, delete its line in the same commit.

## Bearer-JWT surfaces (filter-gated, fail closed)

- `Controllers.Api.Accounting.CashRegisterController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Accounting.CreditNotesController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Accounting.ExchangeRatesController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Accounting.InventoryValuationController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Accounting.InvoiceDetailController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Accounting.InvoicesIssuedController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Accounting.InvoicesReceivedController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Accounting.PaymentsController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Accounting.ReportsController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Accounting.SuppliersController` — `/api/v1/accounting/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Mercatus.ArticlesController` — `/api/v1/mercatus/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Mercatus.ClientAuthController` — `/api/v1/mercatus/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Mercatus.DepartamentosController` — `/api/v1/mercatus/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Mercatus.FamiliesController` — `/api/v1/mercatus/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Mercatus.InventoryController` — `/api/v1/mercatus/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Mercatus.OrderController` — `/api/v1/mercatus/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Mercatus.PromotionsController` — `/api/v1/mercatus/*`, `filters.PublicApiJwtFilter`
- `Controllers.Api.Marketplace.CartController` — `/api/marketplace/*`, `filters.MarketplaceJwtFilter`
- `Controllers.Api.Marketplace.OrderController` — `/api/marketplace/*`, `filters.MarketplaceJwtFilter`
- `Controllers.Api.Marketplace.ProductController` — `/api/marketplace/*`, `filters.MarketplaceJwtFilter`
- `Controllers.Api.Marketplace.ProfileController` — `/api/marketplace/*`, `filters.MarketplaceJwtFilter`
- `Controllers.Api.Marketplace.AuthController` — `/api/marketplace/auth/*` is the filter's exempt login/refresh surface

## Public by design (no session exists yet)

- `Controllers.Api.OAuthController` — `/oauth/*` token endpoint
- `Controllers.Api.App.LoginPageResource` — login page, pre-auth by definition
- `Controllers.Api.App.AppAuthResource` — self-verifies credentials; `/auth/me` and `/auth/logout` are policy carve-outs
- `Controllers.RootRedirectResource` — `/` navigation only
- `Controllers.FallbackResource` — unmatched-path navigation only
- `Controllers.AppEntryResource` — `/app` landing, covered by the `/app/*` authenticated policy

## Cookie-policy surfaces (class-level annotation pending in Phase 3.5.2)

- `Controllers.Api.App.PosResource` — `/api/app/pos/*` is in the secured policy
- `Controllers.Api.App.MiscPagesResource` — `/app/*` policy
- `Controllers.Api.App.LayoutProbeResource` — `/app/_probe`, `/app/*` policy
- `Controllers.Api.App.RecibosRedirectResource` — `/app/recibos`, `/app/*` policy
- `Controllers.Api.App.Reportes.ClientesReportesResource` — `/app/reportes/*` policy; specific role gate tracked as Phase 3.5.2
- `Controllers.Api.App.Reportes.EstacionalidadReportesResource` — `/app/reportes/*` policy; specific role gate tracked as Phase 3.5.2
- `Controllers.Api.App.Reportes.LoyaltyReportesResource` — `/app/reportes/*` policy; specific role gate tracked as Phase 3.5.2

## Known exposure — deferred to Phase 3.5.4 (Bearer identity spike)

These live outside `/api/app/*`, so **no cookie policy covers them today** and
they carry no annotation. Do not annotate them before the spike decides how an
external caller presents identity; document the decision here instead.

- `Controllers.DashboardMetricsController` — `/api/dashboard`
- `Controllers.ProductPerformanceController` — `/api/product-performance`
- `Controllers.SalesTrendController` — `/api/sales-trend`
- `Controllers.StockForecastController` — `/api/stock-forecast`
- `Controllers.QuickActionsController` — `/api/quick-actions`
