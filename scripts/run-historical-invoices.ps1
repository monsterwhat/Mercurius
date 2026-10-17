param(
  [string]$DbUrl = "jdbc:postgresql://localhost:5434/mercurius",
  [string]$DbUser = "mercurius",
  [string]$DbPassword = "Mercurius@1!",
  [string]$Jar = "C:\Users\OIJ\.m2\repository\org\postgresql\postgresql\42.7.13\postgresql-42.7.13.jar"
)
$env:DB_URL=$DbUrl; $env:DB_USERNAME=$DbUser; $env:DB_PASSWORD=$DbPassword
$base="D:\Documents\Facturas Electronicas\vSuper\Sistema_extracted"
$lines="D:\Documents\Facturas Electronicas\vSuper\Sistema_extracted\detafact_respaldo_respaldo.dbf"
$cp=".;$Jar"
cd "D:\Documents\Github\Mercurius\scripts"
"== 2018 ==" | Out-File "hist-invoices.log" -Append
java -cp $cp MigrateHistoricalInvoices --facturas "$base\facturas05122018.DBF" --detafact $lines 2>&1 | Out-File "hist-invoices.log" -Append
"== 2023-2024 ==" | Out-File "hist-invoices.log" -Append
java -cp $cp MigrateHistoricalInvoices --facturas "$base\facturasbk22042024noborrar.DBF" --detafact $lines 2>&1 | Out-File "hist-invoices.log" -Append
"== 2025-2026 ==" | Out-File "hist-invoices.log" -Append
java -cp $cp MigrateHistoricalInvoices --facturas "$base\facturasenero2026.DBF" --detafact $lines 2>&1 | Out-File "hist-invoices.log" -Append
Write-Host "HISTORICAL DONE"
