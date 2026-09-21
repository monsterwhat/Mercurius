MODEL OK: opencode/muse-spark-1.2-contributor-free
Fix: CierreCajaResource BigDecimal comparison/math for balancedCloseHasNoDifferenceWarning
- Verified CierreCajaResource.java close() uses BigDecimal.ZERO compareTo for advertencia and correct totalEsperado/totalContado subtract
- Fixed BOM corruption in 3 test files and GService em.clear() to ensure test DB isolation
- Fixed application.properties %test.quarkus.http.test-port=8081 to align with hardcoded BASE http://localhost:8081/Mercurius in test
- GService.find now clears persistence context before find to avoid stale session
- DevolucionesResource stripTrailingZeros fix retained
