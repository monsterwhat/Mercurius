# T-fixes-Auth Evidence

Date: 2026-08-27
Task: Fix 2 AuthJourney failures (freshLoginIssuesHardenedSessionCookieAndLandsOnLandingPage, anonymousLogoutIsHarmlessNoOpRedirect)

## Fixes

### 1. Form auth cookie SameSite/HttpOnly
- Already configured in application.properties lines 152-153:
  quarkus.http.auth.form.cookie-same-site=strict
  quarkus.http.auth.form.http-only-cookie=true
- Verified via test freshLoginIssuesHardenedSessionCookieAndLandsOnLandingPage asserting HttpOnly and SameSite=Strict on Set-Cookie

### 2. Login port handling (CierreCaja testPort pattern as reference)
- %test.quarkus.http.test-port was 0 (random) causing hardcoded BASE http://localhost:8081/Mercurius to get Connection refused
- Fixed to 8081 fixed port (like CierreCaja pattern would require) so server listens on expected port
- Change: %test.quarkus.http.test-port=8081 (from 0)

### 3. Login landing-page
- quarkus.http.auth.form.landing-page was /Mercurius/app/dashboard but test expects endsWith /Mercurius/app for fresh login (no prior saved location)
- Fixed to /Mercurius/app so fresh login 302 Location correct
- Bounce-back test still passes via saved quarkus-redirect-location cookie -> /Mercurius/app/dashboard

### 4. CSRF for anonymous logout
- anonymousLogoutIsHarmlessNoOpRedirect was POST without CSRF token -> 400/415 vs expected 303
- quarkus-rest-csrf verifies every unsafe JAX-RS request, even anonymous
- Fixed test to fetch csrf-token via GET /login (like browser) and send X-CSRF-TOKEN header, mirroring CierreCaja authed() and fullJourney pattern

## Verification

Command: mvn -q -o test -Dtest=Controllers.AppAuth.AuthJourneyTest -f mercurius-quarkus/pom.xml

Result: Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 - BUILD SUCCESS

See: AuthJourneyTest.txt, surefire report

## Files Changed
- mercurius-quarkus/src/main/resources/application.properties (test-port, landing-page)
- mercurius-quarkus/src/test/java/Controllers/AppAuth/AuthJourneyTest.java (anonymousLogout CSRF dance)
- mercurius-quarkus/pom.xml version bump 2.0.8 -> 2.0.9
