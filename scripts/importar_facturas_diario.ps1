<#
.SYNOPSIS
    Importa a 192.168.1.72 los incrementales nuevos de Z:\Facturas.

.DESCRIPTION
    Flujo (idempotente por clave de Hacienda):
      1. Toma los ZIP `Facturas_incremental_*.zip` que no esten en el estado.
      2. Los extrae a un temporal FUERA de Z: (nunca escribe en el share).
      3. Parsea los XML a CSV con scripts/extraer_xml_emitidas.py.
      4. Copia CSV + SQL a /tmp/dbf_reconciliacion en el servidor.
      5. Ejecuta el SQL en dry-run (rollback) y lee la metrica `nuevas`.
      6. Si hay nuevas, lo ejecuta de verdad y verifica APLICADO.
      7. Solo entonces marca los ZIP como procesados.

    Si cualquier paso falla, los ZIP NO se marcan y se reintentan en la
    proxima corrida. El import es idempotente, asi que repetir es seguro.

.PARAMETER DryRunOnly
    Hace todo menos el commit: util para probar.

.PARAMETER Force
    Procesa los ZIP aunque ya esten marcados (el dry-run mostrara 0 nuevas).

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\importar_facturas_diario.ps1

.NOTES
    Programar a diario despues del backup (22:30), por ejemplo 23:00.
#>
[CmdletBinding()]
param(
    [switch]$DryRunOnly,
    [switch]$Force,
    [string]$Repo = "F:\Documents\GitHub\Mercurius-clean",
    [string]$Share = "Z:\Facturas",
    [string]$Server = "192.168.1.72",
    [string]$SshUser = "alvaro",
    [string]$SshKey = "F:\Documents\prKey.ppk",
    [string]$HostKey = "SHA256:xtXyQV3R03Anr3WoWOh5bac9H/Us5to5w4bpnPHERTc",
    [string]$RemoteDir = "/tmp/dbf_reconciliacion",
    [string]$DbName = "mercurius"
)

$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$Repo      = (Resolve-Path $Repo).Path
$plink     = "C:\Program Files\PuTTY\plink.exe"
$pscp      = "C:\Program Files\PuTTY\pscp.exe"
$logDir    = Join-Path $Repo "logs"
$logFile   = Join-Path $logDir "importacion-facturas.log"
$stateDir  = Join-Path $Repo "scripts\state"
$stateFile = Join-Path $stateDir "importacion-facturas.json"
$workDir   = Join-Path $env:TEMP "mercurius-import"
$xmlDir    = Join-Path $workDir "extraido"
$csvDir    = Join-Path $workDir "csv"

New-Item -ItemType Directory -Force -Path $logDir, $stateDir, $workDir, $xmlDir, $csvDir | Out-Null

function Write-Log {
    param([string]$Message, [string]$Level = "INFO")
    $line = "{0} [{1}] {2}" -f (Get-Date -Format "yyyy-MM-dd HH:mm:ss"), $Level, $Message
    Add-Content -Path $logFile -Value $line -Encoding UTF8
    Write-Host $line
}

function Fail {
    param([string]$Message)
    Write-Log $Message "ERROR"
    exit 1
}

function Invoke-Plink {
    param([string]$Command)
    $out = & $plink -batch -i $SshKey -hostkey $HostKey "$SshUser@$Server" $Command 2>&1
    $code = $LASTEXITCODE
    return [pscustomobject]@{ Output = ($out -join "`n"); Code = $code }
}

# ---------- preflight ----------
foreach ($exe in @($plink, $pscp)) {
    if (-not (Test-Path $exe)) { Fail "No se encontro $exe" }
}
if (-not (Test-Path $SshKey)) { Fail "No se encontro la llave SSH $SshKey" }
if (-not (Test-Path $Share)) { Fail "No existe el share $Share (unidad Z: desconectada?)" }
if (-not (Test-Path (Join-Path $Repo "scripts\extraer_xml_emitidas.py"))) {
    Fail "Falta scripts\extraer_xml_emitidas.py en $Repo"
}

# ---------- estado ----------
$processed = @()
if (Test-Path $stateFile) {
    try {
        $json = Get-Content $stateFile -Raw | ConvertFrom-Json
        if ($json.procesados) { $processed = @($json.procesados) }
    } catch {
        Write-Log "Estado ilegible, se reconstruye: $($_.Exception.Message)" "WARN"
        $processed = @()
    }
}

$todos = @(Get-ChildItem $Share -File -Filter "Facturas_incremental_*.zip" |
    Sort-Object Name)
if ($todos.Count -eq 0) {
    Write-Log "No hay incrementales en $Share"
    exit 0
}

$pendientes = @($todos | Where-Object { $Force -or ($processed -notcontains $_.Name) })

# Un ZIP que se esta escribiendo (backup en curso) se ignora hasta la proxima.
$ahora = Get-Date
$listos = @()
foreach ($z in $pendientes) {
    $edadMin = ($ahora - $z.LastWriteTime).TotalMinutes
    if ($edadMin -lt 5) {
        Write-Log "Se omite $($z.Name): se escribio hace $([math]::Round($edadMin,1)) min (posible backup en curso)" "WARN"
        continue
    }
    $listos += $z
}

if ($listos.Count -eq 0) {
    Write-Log "Nada nuevo para importar"
    exit 0
}

Write-Log ("Pendientes: " + (($listos | ForEach-Object { $_.Name }) -join ", "))

# ---------- extraccion fuera de Z: ----------
if (Test-Path $xmlDir) { Remove-Item $xmlDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $xmlDir | Out-Null
if (Test-Path $csvDir) { Remove-Item $csvDir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $csvDir | Out-Null

Add-Type -AssemblyName System.IO.Compression.FileSystem
$xmlCount = 0
foreach ($z in $listos) {
    try {
        $zip = [System.IO.Compression.ZipFile]::OpenRead($z.FullName)
        try {
            foreach ($e in $zip.Entries) {
                if ($e.FullName.ToLower().EndsWith(".xml") -and $e.Length -gt 0) {
                    $dest = Join-Path $xmlDir ([System.IO.Path]::GetFileName($e.FullName))
                    [System.IO.Compression.ZipFileExtensions]::ExtractToFile($e, $dest, $true)
                    $xmlCount++
                }
            }
        } finally { $zip.Dispose() }
        Write-Log "Extraido $($z.Name)"
    } catch {
        Fail "No se pudo leer $($z.Name) (ZIP incompleto?): $($_.Exception.Message)"
    }
}
if ($xmlCount -eq 0) { Fail "Los ZIP no contenian XML" }
Write-Log "XML extraidos: $xmlCount"

# ---------- parseo ----------
$env:MERCURIUS_XML_DIR = $xmlDir
$env:MERCURIUS_CSV_DIR = $csvDir
try {
    $py = & python (Join-Path $Repo "scripts\extraer_xml_emitidas.py") 2>&1
    if ($LASTEXITCODE -ne 0) { Fail "Fallo el parser: $($py -join ' ')" }
    $resumen = ($py | Where-Object { $_ -match "claves" } | Select-Object -Last 1)
    Write-Log "Parser: $resumen"
} finally {
    Remove-Item Env:\MERCURIUS_XML_DIR -ErrorAction SilentlyContinue
    Remove-Item Env:\MERCURIUS_CSV_DIR -ErrorAction SilentlyContinue
}

$csvs = @("xml_enc.csv", "xml_res.csv", "xml_lin.csv")
foreach ($c in $csvs) {
    if (-not (Test-Path (Join-Path $csvDir $c))) { Fail "El parser no genero $c" }
}

# ---------- copia al servidor ----------
$mk = Invoke-Plink "mkdir -p $RemoteDir && chmod 755 $RemoteDir"
if ($mk.Code -ne 0) { Fail "No se pudo preparar $RemoteDir : $($mk.Output)" }

foreach ($c in $csvs) {
    & $pscp -batch -i $SshKey -hostkey $HostKey (Join-Path $csvDir $c) "${SshUser}@${Server}:$RemoteDir/$c" 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { Fail "Fallo la copia de $c" }
}
$sqlName = "importar_xml_emitidas.sql"
& $pscp -batch -i $SshKey -hostkey $HostKey (Join-Path $Repo "scripts\$sqlName") "${SshUser}@${Server}:$RemoteDir/$sqlName" 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail "Fallo la copia del SQL" }

$fix = Invoke-Plink "chmod 644 $RemoteDir/*"
if ($fix.Code -ne 0) { Fail "No se pudieron ajustar permisos en $RemoteDir" }

# ---------- dry-run ----------
$dry = Invoke-Plink "sudo -n -u postgres psql -d $DbName -v aplicar=false -f $RemoteDir/$sqlName 2>&1"
if ($dry.Code -ne 0) { Fail "Fallo el dry-run: $($dry.Output)" }
$mNuevas = [regex]::Match($dry.Output, "(?m)^\s*nuevas\s*\|\s*(\d+)")
$mLineas = [regex]::Match($dry.Output, "(?m)^\s*lineas_nuevas\s*\|\s*(\d+)")
$nuevas = if ($mNuevas.Success) { [int]$mNuevas.Groups[1].Value } else { -1 }
$lineas = if ($mLineas.Success) { [int]$mLineas.Groups[1].Value } else { -1 }
if ($nuevas -lt 0) { Fail "No se pudo leer la metrica 'nuevas' del dry-run: $($dry.Output)" }
if (-not ($dry.Output -match "ROLLBACK")) { Fail "El dry-run no hizo rollback: $($dry.Output)" }
Write-Log "Dry-run: nuevas=$nuevas lineas=$lineas"

if ($DryRunOnly) {
    Write-Log "DryRunOnly: no se aplica ni se marcan los ZIP"
    exit 0
}
if ($nuevas -eq 0) {
    $processed = @($processed + ($listos | ForEach-Object { $_.Name }) | Select-Object -Unique)
    @{ procesados = $processed } | ConvertTo-Json | Set-Content $stateFile -Encoding UTF8
    Write-Log "Sin comprobantes nuevos; ZIP marcados como procesados"
    exit 0
}

# ---------- aplicar ----------
$apply = Invoke-Plink "sudo -n -u postgres psql -d $DbName -v aplicar=true -f $RemoteDir/$sqlName 2>&1"
if ($apply.Code -ne 0) { Fail "Fallo la aplicacion: $($apply.Output)" }
if (-not ($apply.Output -match "APLICADO")) { Fail "El SQL no confirmo APLICADO: $($apply.Output)" }

$processed = @($processed + ($listos | ForEach-Object { $_.Name }) | Select-Object -Unique)
@{ procesados = $processed } | ConvertTo-Json | Set-Content $stateFile -Encoding UTF8

Write-Log "APLICADO: +$nuevas comprobantes, +$lineas lineas (ZIP: $(($listos | ForEach-Object { $_.Name }) -join ', '))"
exit 0
