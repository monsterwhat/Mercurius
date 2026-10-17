while (Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -like "*MigrateHaciendaStatus*" }) {
    Start-Sleep -Seconds 20
}
$env:DB_URL = "jdbc:postgresql://localhost:5434/mercurius"
$env:DB_USERNAME = "mercurius"
$env:DB_PASSWORD = "Mercurius@1!"
Set-Location "D:\Documents\Github\Mercurius\scripts"
java -cp ".;C:\Users\OIJ\.m2\repository\org\postgresql\postgresql\42.7.13\postgresql-42.7.13.jar" MigrateStock 2>&1 | Out-File "stock-run.log"
Write-Host "STOCK DONE"
