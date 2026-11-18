<#
.SYNOPSIS
    Verifica la integridad de los datos de facturación en 192.168.1.72.

.DESCRIPTION
    Corre scripts/verificar_integridad.sql sobre una ventana (por defecto los
    últimos 2 días) y devuelve:

       0  todo bien (o solo avisos)
       2  alguna falla dura: neta+iva<>total, sin resumen, sin líneas, IVA en cero

    Las fallas duras hacen que psql aborte con RAISE EXCEPTION, así que el
    código de salida es el de psql.

    Pensado para correr a diario después de Mercurius-ImportarFacturas, y
    también a mano.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verificar_integridad_diario.ps1

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\verificar_integridad_diario.ps1 -Desde 2026-11-01 -Hasta 2026-12-01

.NOTES
    Programar a diario 23:30 (después del import de las 23:00).
#>
[CmdletBinding()]
param(
    [string]$Repo = "F:\Documents\GitHub\Mercurius-clean",
    [string]$Server = "192.168.1.72",
    [string]$SshUser = "alvaro",
    [string]$SshKey = "F:\Documents\prKey.ppk",
    [string]$HostKey = "SHA256:xtXyQV3R03Anr3WoWOh5bac9H/Us5to5w4bpnPHERTc",
    [string]$RemoteDir = "/tmp/dbf_reconciliacion",
    [string]$DbName = "mercurius",
    [int]$Dias = 2,
    [string]$Desde,
    [string]$Hasta
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$plink = "C:\Program Files\PuTTY\plink.exe"
$pscp = "C:\Program Files\PuTTY\pscp.exe"
$logDir = Join-Path $Repo "logs"
$logFile = Join-Path $logDir "integridad.log"
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $line = "{0} [{1}] {2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Level, $Message
    Add-Content -Path $logFile -Value $line -Encoding UTF8
    Write-Host $line
}

foreach ($exe in @($plink, $pscp)) {
    if (-not (Test-Path $exe)) { Write-Log "No se encontró $exe" "ERROR"; exit 2 }
}
if (-not (Test-Path $SshKey)) { Write-Log "No se encontró la llave $SshKey" "ERROR"; exit 2 }

if (-not $Desde) { $Desde = (Get-Date).AddDays(-$Dias).ToString("yyyy-MM-dd") }
if (-not $Hasta) { $Hasta = (Get-Date).AddDays(1).ToString("yyyy-MM-dd") }

$sql = Join-Path $Repo "scripts\verificar_integridad.sql"
if (-not (Test-Path $sql)) { Write-Log "Falta $sql" "ERROR"; exit 2 }

$remote = "$RemoteDir/verificar_integridad.sql"

& $pscp -batch -i $SshKey -hostkey $HostKey $sql "${SshUser}@${Server}:$remote" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Write-Log "No se pudo copiar el SQL al servidor" "ERROR"; exit 2 }

$cmd = "sudo -n -u postgres psql -d $DbName -v desde=$Desde -v hasta=$Hasta -f $remote 2>&1"
$out = & $plink -batch -i $SshKey -hostkey $HostKey "$SshUser@$Server" $cmd 2>&1
$code = $LASTEXITCODE
$texto = ($out -join "`n")

foreach ($linea in ($out | Where-Object { $_ -match "^\s*(comprobantes|lineas|iva_resumen\w*|sin_clave|lineas_sin_articulo|movimientos_repetidos)\s*\|" })) {
    Write-Log ("  " + ($linea -replace "\s+", " ").Trim())
}

if ($code -ne 0) {
    $falla = ($out | Where-Object { $_ -match "INTEGRIDAD \d" } | Select-Object -First 1)
    if (-not $falla) { $falla = ($texto -split "`n" | Select-Object -Last 3) -join " " }
    $msg = "Integridad FALLO {0} a {1}: {2}" -f $Desde, $Hasta, $falla.Trim()
    Write-Log $msg "ERROR"
    exit 2
}

$ok = "Integridad OK {0} a {1}" -f $Desde, $Hasta
Write-Log $ok
exit 0
