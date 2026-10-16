param(
  [string]$CsvDir = "D:\Documents\Facturas Electronicas\vSuper\csv",
  [string]$DbUrl = "jdbc:postgresql://localhost:5434/mercurius",
  [string]$DbUser = "mercurius",
  [string]$DbPassword = "Mercurius@1!",
  [string]$PostgresJar = "C:\Users\OIJ\.m2\repository\org\postgresql\postgresql\42.7.13\postgresql-42.7.13.jar"
)

# Wait for the already-running invoice importer, if any.
while ($true) {
    $p = Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*MigrateInvoices*" }
    if (-not $p) { break }
    Start-Sleep -Seconds 30
}

$env:DB_URL = $DbUrl
$env:DB_USERNAME = $DbUser
$env:DB_PASSWORD = $DbPassword
Set-Location "$PSScriptRoot"

# Hacienda status
javac -cp $PostgresJar "MigrateHaciendaStatus.java" 2>&1 | Out-File "$PSScriptRoot\hacienda-build.log"
if ($LASTEXITCODE -eq 0) {
    java -cp `".;$PostgresJar`" MigrateHaciendaStatus 2>&1 | Out-File "$PSScriptRoot\hacienda-run.log"
} else { Write-Host "Hacienda compile failed" }

# Stock snapshot
javac -cp $PostgresJar "MigrateStock.java" 2>&1 | Out-File "$PSScriptRoot\stock-build.log"
if ($LASTEXITCODE -eq 0) {
    java -cp `".;$PostgresJar`" MigrateStock 2>&1 | Out-File "$PSScriptRoot\stock-run.log"
} else { Write-Host "Stock compile failed" }

Write-Host "RUN ALL DONE"
