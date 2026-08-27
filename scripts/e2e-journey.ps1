<#
.SYNOPSIS
    F3 north-star end-to-end acceptance journey for Mercurius (JSF -> API migration).

.DESCRIPTION
    Executes THE acceptance journey that defines "the app works" for F3
    verification, in order, stopping on the first failed step with its name:

      1. GET  /Mercurius/login                          -> 200 + j_security_check form
      2. POST /Mercurius/j_security_check               -> 302 + quarkus-credential cookie
      3. POST /Mercurius/api/app/articulos              -> 201 (article created)
      4. POST /Mercurius/api/app/inventario/ajustes     -> 200/201 (positive stock)
      5. POST /Mercurius/api/app/facturas-recibidas/upload (v4.4 XML fixture)
           + GET /{id}/prevalidacion                    -> 200 + isValid=true (PASS)
      6. POS sale: scan x2 -> cart qty=2 -> override-authorize ->
         payment-entries [efectivo+SINPE] -> facturar (puntos redemption)
           -> 200 {pdfUrl} -> GET pdfUrl                -> %PDF- magic bytes
      7. Recibos action: POST /api/app/recibos/{id}/pagar on a seeded
         credito receipt                                -> 200 + paid flipped true
      8. POST /api/app/export xlsx (stock-alerts)      -> PKx03x04 magic bytes
         POST /api/app/export pdf  (articulos)         -> %PDF- magic bytes
      9. POST /api/app/auth/logout                     -> 303 -> /Mercurius/login;
         replay old cookie on /me                      -> 401/302

    Every payload mirrors the current Controllers/Api/App/* contracts:
      - ArticuloResource.ArticuloForm          (create article)
      - InventarioResource.AjusteRequest       (create adjustment)
      - FacturasRecibidasResource /upload      (multipart field "files")
      - PosResource ScanRequest / OverrideAuthRequest / FacturarRequest
      - ExportResource form params type|dataset (POST, form-urlencoded)
      - AppAuthResource /logout (303 seeOther) and /me (401 when anonymous)

.PARAMETER BaseUrl
    Scheme+authority only (root path /Mercurius is appended by the script).
    Default: http://localhost:8081

.PARAMETER Username / Password
    Seeded admin credentials (import-test.sql: admin / admin123).

.PARAMETER ClientCode
    Seeded client code used for the POS sale ('Cliente Contado', code 1 on a
    fresh import-test.sql database).

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File scripts\e2e-journey.ps1

.NOTES
    Exit code 0 = all steps passed; 1 = first failing step (its name printed).
    RED by design until the migration completes (auth-gated /api/app surface,
    recibos pay endpoint, etc.). This script IS the definition of done.
    Pure ASCII on purpose: PowerShell 5.1 reads BOM-less files as ANSI and
    multi-byte characters would corrupt parsing.
#>
param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$Username = "admin",
    [string]$Password = "admin123",
    [int]$ClientCode = 1
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

# ======================================================================
# Tiny HttpClient harness: exact status codes (no redirect following),
# automatic cookie collection, byte-accurate bodies for magic-byte checks.
# ======================================================================

function New-E2ESession {
    $cookies = New-Object System.Net.CookieContainer
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.CookieContainer = $cookies
    $handler.AllowAutoRedirect = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(90)
    return @{ Client = $client; Cookies = $cookies }
}

function Invoke-E2E {
    param(
        [hashtable]$Session,
        [ValidateSet('GET','POST','PUT','DELETE','HEAD')][string]$Method,
        [string]$Path,
        [byte[]]$BodyBytes,
        [string]$ContentType,
        [hashtable]$Headers = @{}
    )
    $uri = if ($Path.StartsWith('http')) { $Path } else { $BaseUrl + $Path }
    $request = New-Object System.Net.Http.HttpRequestMessage(
        ([System.Net.Http.HttpMethod]::new($Method)), $uri)
    if ($PSBoundParameters.ContainsKey('BodyBytes') -and $null -ne $BodyBytes) {
        $request.Content = New-Object System.Net.Http.ByteArrayContent(, $BodyBytes)
        if ($ContentType) {
            $request.Content.Headers.TryAddWithoutValidation('Content-Type', $ContentType) | Out-Null
        }
    }
    foreach ($key in $Headers.Keys) {
        $request.Headers.TryAddWithoutValidation($key, [string]$Headers[$key]) | Out-Null
    }
    $response = $Session.Client.SendAsync($request).GetAwaiter().GetResult()
    $bytes = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
    $location = $null
    if ($null -ne $response.Headers.Location) { $location = $response.Headers.Location.ToString() }
    return @{
        Status   = [int]$response.StatusCode
        Bytes    = $bytes
        Text     = [System.Text.Encoding]::UTF8.GetString($bytes)
        Location = $location
    }
}

function Get-Json([hashtable]$Response) {
    return $Response.Text | ConvertFrom-Json
}

function Test-CookiePresent([hashtable]$Session, [string]$Name) {
    $uri = New-Object Uri($BaseUrl)
    $found = $Session.Cookies.GetCookies($uri) | Where-Object { $_.Name -eq $Name }
    return ($null -ne $found)
}

function Get-CookieValue([hashtable]$Session, [string]$Name) {
    $uri = New-Object Uri($BaseUrl)
    $found = $Session.Cookies.GetCookies($uri) | Where-Object { $_.Name -eq $Name }
    if ($null -eq $found) { return $null }
    return $found.Value
}

function Add-CookieManually([hashtable]$Session, [string]$Name, [string]$Value) {
    $uri = New-Object Uri($BaseUrl + "/")
    $cookie = New-Object System.Net.Cookie($Name, $Value, "/", $uri.Host)
    $Session.Cookies.Add($uri, $cookie)
}

function Test-MagicBytes([byte[]]$Bytes, [byte[]]$Magic) {
    if ($null -eq $Bytes -or $Bytes.Length -lt $Magic.Length) { return $false }
    for ($i = 0; $i -lt $Magic.Length; $i++) {
        if ($Bytes[$i] -ne $Magic[$i]) { return $false }
    }
    return $true
}

# -- Step runner: stops on first failure with the step name ------------
$script:StepNumber = 0
$script:StepName = ''

function Start-Step([string]$Name) {
    $script:StepNumber++
    $script:StepName = $Name
    Write-Host ("`n=== STEP {0}: {1} ===" -f $script:StepNumber, $Name) -ForegroundColor Cyan
}

function Assert-Journey([bool]$Condition, [string]$Detail) {
    if (-not $Condition) {
        throw ("STEP {0} FAILED [{1}]: {2}" -f $script:StepNumber, $script:StepName, $Detail)
    }
    Write-Host ("  ok: {0}" -f $Detail) -ForegroundColor Green
}

function Assert-Status([hashtable]$Response, [int[]]$Expected) {
    $hit = $Expected -contains $Response.Status
    Assert-Journey $hit ("expected status {0}, got {1}" -f ($Expected -join '|'), $Response.Status)
}

# -- Multipart builder (PS 5.1 has no -Form; build RFC 2046 by hand) ---
function Send-MultipartXml {
    param([hashtable]$Session, [string]$Path, [string]$FileName, [byte[]]$FileBytes)
    $boundary = '----e2eJourney' + [Guid]::NewGuid().ToString('N')
    $nl = "`r`n"
    $partHead = "--$boundary$nl" +
        "Content-Disposition: form-data; name=`"files`"; filename=`"$FileName`"$nl" +
        "Content-Type: application/xml$nl$nl"
    $partTail = "$nl--$boundary--$nl"
    $body = [System.Text.Encoding]::UTF8.GetBytes($partHead) +
        $FileBytes +
        [System.Text.Encoding]::UTF8.GetBytes($partTail)
    return Invoke-E2E -Session $Session -Method POST -Path $Path `
        -BodyBytes $body -ContentType "multipart/form-data; boundary=$boundary"
}

# ======================================================================
# Fixture loading: reuse the committed v4.4-shaped fixture and re-stamp
# Clave/Consecutivo uniquely per run (the parser skips duplicate
# consecutivos). A second variant flips CondicionVenta to credit (02) with
# PlazoCredito 30 so it lands in the Recibos pendientes bucket for step 7.
# ======================================================================

$repoRoot = Split-Path -Parent $PSScriptRoot
$fixturePath = Join-Path $repoRoot 'mercurius-quarkus\src\test\resources\fixtures\recibidos\factura-recibida-valida.xml'
Assert-Journey (Test-Path -LiteralPath $fixturePath) "fixture not found: $fixturePath"
$fixtureXml = [System.IO.File]::ReadAllText($fixturePath)

# Exactly 19 random digits, string-built (keeps the Clave at the required
# 50 digits without overflowing Int64 in Get-Random).
$rand19 = -join (1..19 | ForEach-Object { Get-Random -Minimum 0 -Maximum 10 })
$rand19b = -join (1..19 | ForEach-Object { Get-Random -Minimum 0 -Maximum 10 })
$rand6 = '{0:D6}' -f (Get-Random -Minimum 0 -Maximum 1000000)
$rand6b = '{0:D6}' -f (Get-Random -Minimum 0 -Maximum 1000000)

$consecContado = "00100001047777$rand6"
# 31-digit fixed prefix + 19 random digits = exactly the required 50-digit Clave.
$claveContado = '5062508250000010100010000000101' + $rand19
$xmlContado = $fixtureXml.Replace('00100001040000000036', $consecContado)
$xmlContado = [regex]::Replace($xmlContado, '>\d{50}<', ">$claveContado<")

$consecCredito = "00100001046666$rand6b"
$claveCredito = '5062508250000010100010000000101' + $rand19b
$xmlCredito = $fixtureXml.Replace('00100001040000000036', $consecCredito)
$xmlCredito = [regex]::Replace($xmlCredito, '>\d{50}<', ">$claveCredito<")
$xmlCredito = $xmlCredito.Replace('<CondicionVenta>01<', '<CondicionVenta>02<')
$xmlCredito = $xmlCredito.Replace('<PlazoCredito>0<', '<PlazoCredito>30<')

$barcode = 'E2EJ' + (Get-Date -Format 'HHmmss') + (Get-Random -Minimum 100 -Maximum 999)

$zipMagic = [byte[]](0x50, 0x4B, 0x03, 0x04)     # PKx03x04 (xlsx)
$pdfMagic = [byte[]](0x25, 0x50, 0x44, 0x46, 0x2D) # %PDF-

# ======================================================================
# THE JOURNEY
# ======================================================================

try {
    # -- STEP 1: login page renders the j_security_check form ----------
    Start-Step 'Login page renders j_security_check form'
    $session = New-E2ESession
    $loginPage = Invoke-E2E -Session $session -Method GET -Path '/Mercurius/login'
    Assert-Status $loginPage @(200)
    Assert-Journey ($loginPage.Text -match 'j_security_check') '200 body contains j_security_check'

    # -- STEP 2: form login -> 302 + quarkus-credential cookie ---------
    Start-Step 'Form login admin/admin123'
    $credBody = [System.Text.Encoding]::UTF8.GetBytes(
        "j_username=$Username&j_password=$Password")
    $login = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/j_security_check' `
        -BodyBytes $credBody -ContentType 'application/x-www-form-urlencoded'
    Assert-Status $login @(302)
    Assert-Journey (Test-CookiePresent $session 'quarkus-credential') `
        '302 + quarkus-credential cookie issued'

    # CSRF token (defensive parity with the test suite: header sent only
    # when the quarkus-rest-csrf cookie exists).
    $csrf = Get-CookieValue $session 'csrftoken'
    if (-not $csrf) { $csrf = Get-CookieValue $session 'csrf-token' }
    $postHeaders = @{}
    if ($csrf) { $postHeaders['X-CSRF-TOKEN'] = $csrf }

    # -- STEP 3: create article -> 201 ----------------------------------
    Start-Step 'Create articulo (cabys 501010101-style, precio)'
    $articuloPayload = @{
        nombre                = "E2E Journey Articulo $barcode"
        codigoBarra           = $barcode
        descripcion           = 'Created by scripts/e2e-journey.ps1'
        unidadMedida          = 'Unid'
        unidadMedidaComercial = 'Unidad'
        departamentoId        = 1   # seeded row: 'Departamento General'
        familiaId             = 1   # seeded row: 'Familia General'
        cabysCodigo           = '501010101'
        precioCostoSinIVA     = 10000
        porcentajeUtilidad    = 20
        exento                = $false
        stockOptimo           = 50
        diasStockSeguridad    = 7
    } | ConvertTo-Json
    $createArt = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/api/app/articulos' `
        -BodyBytes ([System.Text.Encoding]::UTF8.GetBytes($articuloPayload)) `
        -ContentType 'application/json' -Headers $postHeaders
    Assert-Status $createArt @(201)
    $articulo = Get-Json $createArt
    Assert-Journey ($null -ne $articulo.data.codigo -and $articulo.data.codigo -gt 0) `
        "201 data.codigo=$($articulo.data.codigo)"
    $articuloId = [long]$articulo.data.codigo

    # -- STEP 4: positive stock via inventario upload-or-adjust ---------
    Start-Step 'Inventario ajuste creates positive stock'
    $ajustePayload = @{
        articuloId     = $articuloId
        cantidad       = 25
        tipoMovimiento = 'Ajuste manual'
        notas          = 'E2E journey initial stock'
    } | ConvertTo-Json
    $ajuste = Invoke-E2E -Session $session -Method POST `
        -Path '/Mercurius/api/app/inventario/ajustes' `
        -BodyBytes ([System.Text.Encoding]::UTF8.GetBytes($ajustePayload)) `
        -ContentType 'application/json' -Headers $postHeaders
    Assert-Status $ajuste @(200, 201)
    Assert-Journey ($null -ne (Get-Json $ajuste).data) 'positive stock registered'

    # -- STEP 5: upload valid v4.4 XML fixture + prevalidation PASS -----
    Start-Step 'Facturas recibidas upload v4.4 fixture + prevalidation PASS'
    $upContado = Send-MultipartXml -Session $session -Path '/Mercurius/api/app/facturas-recibidas/upload' `
        -FileName 'e2e-contado.xml' -FileBytes ([System.Text.Encoding]::UTF8.GetBytes($xmlContado))
    Assert-Status $upContado @(200, 201)
    $upJson = Get-Json $upContado
    Assert-Journey ($upJson.data.resultados[0].exito -eq $true) 'upload exitoso=true'
    Assert-Journey ($upJson.data.exitosos -ge 1) "exitosos=$($upJson.data.exitosos)"

    function Find-FacturaId([hashtable]$Sess, [string]$Consecutivo) {
        $resp = Invoke-E2E -Session $Sess -Method GET `
            -Path "/Mercurius/api/app/facturas-recibidas?bucket=todas&q=$Consecutivo"
        Assert-Status $resp @(200)
        $rows = (Get-Json $resp).data
        foreach ($row in $rows) {
            if ($row.consecutivo -eq $Consecutivo) { return [long]$row.id }
        }
        throw ("STEP {0} FAILED [{1}]: consecutivo {2} not persisted" -f
            $script:StepNumber, $script:StepName, $Consecutivo)
    }
    function Get-PaidFlag([hashtable]$Sess, [string]$Consecutivo) {
        $resp = Invoke-E2E -Session $Sess -Method GET `
            -Path "/Mercurius/api/app/facturas-recibidas?bucket=todas&q=$Consecutivo"
        Assert-Status $resp @(200)
        foreach ($row in (Get-Json $resp).data) {
            if ($row.consecutivo -eq $Consecutivo) { return [bool]$row.paid }
        }
        return $false
    }

    $facturaContadoId = Find-FacturaId $session $consecContado
    $preval = Invoke-E2E -Session $session -Method GET `
        -Path "/Mercurius/api/app/facturas-recibidas/$facturaContadoId/prevalidacion"
    Assert-Status $preval @(200)
    $panel = Get-Json $preval
    Assert-Journey ($panel.data.isValid -eq $true -and $panel.data.errorCount -eq 0) `
        "prevalidation PASS (isValid=true, errorCount=$($panel.data.errorCount))"

    # Credit variant for the Recibos step (7): CondicionVenta 02 + plazo 30.
    Start-Step 'Seed credito receipt for Recibos (upload variant)'
    $upCredito = Send-MultipartXml -Session $session -Path '/Mercurius/api/app/facturas-recibidas/upload' `
        -FileName 'e2e-credito.xml' -FileBytes ([System.Text.Encoding]::UTF8.GetBytes($xmlCredito))
    Assert-Status $upCredito @(200, 201)
    Assert-Journey (((Get-Json $upCredito).data.resultados[0].exito) -eq $true) 'credito upload exitoso=true'
    $reciboId = Find-FacturaId $session $consecCredito
    Assert-Journey ((Get-PaidFlag $session $consecCredito) -eq $false) `
        'seeded receipt starts unpaid (paid=false)'

    # -- STEP 6: POS sale with override, puntos redemption, split pay ---
    Start-Step 'POS scan barcode x2'
    $scanBody = @{ codigoBarra = $barcode; cantidad = 1 } | ConvertTo-Json
    foreach ($i in 1..2) {
        $scan = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/api/app/pos/scan' `
            -BodyBytes ([System.Text.Encoding]::UTF8.GetBytes($scanBody)) `
            -ContentType 'application/json' -Headers $postHeaders
        Assert-Status $scan @(200)
    }
    Assert-Journey $true 'two scans accepted (200 x2)'

    Start-Step 'Cart snapshot shows qty=2'
    $cartResp = Invoke-E2E -Session $session -Method GET -Path '/Mercurius/api/app/pos/cart'
    Assert-Status $cartResp @(200)
    $cart = Get-Json $cartResp
    $qty = [double]$cart.data.items[0].cantidad
    Assert-Journey ($qty -eq 2) "items[0].cantidad=$qty (expected 2)"

    Start-Step 'Select client + stage split payments (efectivo + SINPE)'
    $clientBody = @{ clientCode = $ClientCode } | ConvertTo-Json
    $clientResp = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/api/app/pos/client' `
        -BodyBytes ([System.Text.Encoding]::UTF8.GetBytes($clientBody)) `
        -ContentType 'application/json' -Headers $postHeaders
    Assert-Status $clientResp @(200)

    $total = 0.0
    if ($null -ne $cart.data.totalCarrito) { $total = [double]$cart.data.totalCarrito }
    $pagosPayload = @(
        @{ metodoPago = '01'; monto = $total },   # Efectivo covers the total
        @{ metodoPago = '06'; monto = 1000 }      # SINPE Movil -> vuelto 1000
    ) | ConvertTo-Json
    $payResp = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/api/app/pos/payment-entries' `
        -BodyBytes ([System.Text.Encoding]::UTF8.GetBytes($pagosPayload)) `
        -ContentType 'application/json' -Headers $postHeaders
    Assert-Status $payResp @(200)
    $payment = Get-Json $payResp
    Assert-Journey ($payment.data.vuelto -ge 0) "vuelto=$($payment.data.vuelto) >= 0"

    Start-Step 'Supervisor override authorization (admin legacy parity)'
    $overrideBody = @{ username = $Username; password = $Password } | ConvertTo-Json
    $override = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/api/app/pos/override-authorize' `
        -BodyBytes ([System.Text.Encoding]::UTF8.GetBytes($overrideBody)) `
        -ContentType 'application/json' -Headers $postHeaders
    Assert-Status $override @(200)
    Assert-Journey (((Get-Json $override).data.authorizedBy) -eq $Username) `
        "authorizedBy=$Username"

    Start-Step 'Facturar with puntos redemption -> 200 {pdfUrl}'
    $facturarBody = @{ tipoDocumento = '04'; puntosARedimir = 10 } | ConvertTo-Json
    $facturar = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/api/app/pos/facturar' `
        -BodyBytes ([System.Text.Encoding]::UTF8.GetBytes($facturarBody)) `
        -ContentType 'application/json' -Headers $postHeaders
    Assert-Status $facturar @(200)
    $factura = Get-Json $facturar
    Assert-Journey (-not [string]::IsNullOrWhiteSpace($factura.data.pdfUrl)) `
        "pdfUrl=$($factura.data.pdfUrl)"

    Start-Step 'Invoice PDF streams %PDF- magic bytes'
    $pdfResp = Invoke-E2E -Session $session -Method GET -Path $factura.data.pdfUrl
    Assert-Status $pdfResp @(200)
    Assert-Journey (Test-MagicBytes $pdfResp.Bytes $pdfMagic) 'body starts with %PDF-'

    # -- STEP 7: Recibos pay/process flips the seeded receipt -----------
    Start-Step 'Recibos pagar flips seeded receipt state'
    $pagar = Invoke-E2E -Session $session -Method POST `
        -Path "/Mercurius/api/app/recibos/$reciboId/pagar" `
        -BodyBytes ([System.Text.Encoding]::UTF8.GetBytes('{}')) `
        -ContentType 'application/json' -Headers $postHeaders
    Assert-Status $pagar @(200)
    Assert-Journey ((Get-PaidFlag $session $consecCredito) -eq $true) `
        'receipt state flipped paid=false -> true'

    # -- STEP 8: exports stream real workbook/PDF magic bytes -----------
    Start-Step 'Export xlsx (stock-alerts) -> ZIP magic bytes'
    $xlsx = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/api/app/export' `
        -BodyBytes ([System.Text.Encoding]::ASCII.GetBytes('type=xlsx&dataset=stock-alerts')) `
        -ContentType 'application/x-www-form-urlencoded'
    Assert-Status $xlsx @(200)
    Assert-Journey (Test-MagicBytes $xlsx.Bytes $zipMagic) 'body starts with PKx03x04 (ZIP/xlsx)'

    Start-Step 'Export pdf (articulos) -> %PDF- magic bytes'
    $pdfExport = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/api/app/export' `
        -BodyBytes ([System.Text.Encoding]::ASCII.GetBytes('type=pdf&dataset=articulos')) `
        -ContentType 'application/x-www-form-urlencoded'
    Assert-Status $pdfExport @(200)
    Assert-Journey (Test-MagicBytes $pdfExport.Bytes $pdfMagic) 'body starts with %PDF-'

    # -- STEP 9: logout invalidates the session server-side -------------
    Start-Step 'Logout -> 303 and old cookie replay rejected'
    $oldCredential = Get-CookieValue $session 'quarkus-credential'
    Assert-Journey (-not [string]::IsNullOrWhiteSpace($oldCredential)) 'captured old credential cookie'
    $logout = Invoke-E2E -Session $session -Method POST -Path '/Mercurius/api/app/auth/logout' `
        -Headers $postHeaders
    Assert-Status $logout @(303)
    Assert-Journey ($null -ne $logout.Location -and
                    $logout.Location.EndsWith('/Mercurius/login')) `
        "303 Location=$($logout.Location)"

    $replay = New-E2ESession
    Add-CookieManually $replay 'quarkus-credential' $oldCredential
    $me = Invoke-E2E -Session $replay -Method GET -Path '/Mercurius/api/app/auth/me'
    Assert-Status $me @(401, 302)
    Assert-Journey ($me.Status -eq 401 -or $me.Status -eq 302) `
        "replayed cookie rejected with $($me.Status)"

    Write-Host "`n======================================================" -ForegroundColor Cyan
    Write-Host " E2E JOURNEY PASSED - all $script:StepNumber steps green." -ForegroundColor Green
    Write-Host "======================================================`n" -ForegroundColor Cyan
    exit 0
}
catch {
    Write-Host "`n======================================================" -ForegroundColor Yellow
    Write-Host " E2E JOURNEY RED" -ForegroundColor Red
    Write-Host $_.Exception.Message -ForegroundColor Red
    Write-Host "======================================================`n" -ForegroundColor Yellow
    exit 1
}
