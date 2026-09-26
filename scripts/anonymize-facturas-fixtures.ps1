<#
.SYNOPSIS
    Generates anonymized real-invoice test fixtures for Mercurius.

.DESCRIPTION
    Reads real Costa Rica electronic invoices (v4.3) from an external folder,
    strips every direct and indirect identifier, and emits two fixture sets:

      fixtures/reales/v4.3/  - anonymized, signature-stripped, as-is structure
      fixtures/reales/v4.4/  - derived, strictly XSD-valid against
                               FacturaElectronica_V4.4.xsd

    What is removed or replaced (never committed):
      - ds:Signature block: X.509 cert, RSA modulus, signing time
      - Emisor / Receptor <Nombre> and <NombreComercial>
      - Identificacion/<Numero> (RUC / cedula)
      - CorreoElectronico, NumTelefono
      - Ubicacion/<Barrio> and <OtrasSenas>
      - Clave (rebuilt, because a real Clave embeds the real RUC in digits 10-21)
      - InformacionReferencia/<Clave> (references a real Clave)
      - original file names (several embed the RUC)

    What is intentionally preserved (the reason this data is valuable):
      - LineaDetalle <Detalle>          real product / article names
      - LineaDetalle <Codigo>           real 13-digit GTIN barcodes
      - CodigoComercial Tipo + Codigo  real supplier/buyer item codes
      - UnidadMedida, UnidadMedidaComercial, Cantidad, PrecioUnitario
      - Descuento / Impuesto arithmetic, ResumenFactura totals
      - Real-world irregularities: multiple Descuento per line, absent
        Ubicacion on Receptor, trailing whitespace in <Nombre>, 11-digit
        cedula with Tipo 03, numeric Barrio codes

    Replacement values are shape-preserving: a 9-digit cedula starting 1 stays
    a 9-digit cedula starting 1, so ComprobantesRecibidosPrevalidationService's
    format branches ([01]\d{8,9}, 3\d{9,11}, \d{11,12}) keep selecting the same
    path and the fixture keeps passing for the same reason the original did.

.PARAMETER SourceDir
    Folder of source XML invoices (v4.3, signed).

.PARAMETER OutRoot
    Destination root for fixtures/reales.

.PARAMETER RepoRoot
    Mercurius repository root, used to locate the XSDs for validation.
#>
[CmdletBinding()]
param(
    [string]$SourceDir = "D:\Documents\Facturas Electronicas\Todas",
    [string]$RepoRoot  = "D:\Documents\Github\Mercurius",
    [string]$OutRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $OutRoot) { $OutRoot = Join-Path $RepoRoot "app\src\test\resources\fixtures\reales" }
$V43Dir = Join-Path $OutRoot "v4.3"
$V44Dir = Join-Path $OutRoot "v4.4"
foreach ($d in @($V43Dir, $V44Dir)) {
    if (Test-Path -LiteralPath $d) { Remove-Item -LiteralPath $d -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

# ---------------------------------------------------------------------------
# 0. Accented characters are built from code points, and map keys are matched
#    after diacritic stripping, so this script stays pure ASCII and does not
#    depend on a UTF-8 BOM (PowerShell 5.1 reads BOM-less .ps1 as ANSI).
# ---------------------------------------------------------------------------
$script:NTILDE = [string][char]0x00D1   # N tilde
$script:UACUTE = [string][char]0x00FA   # u acute

function ConvertTo-Comparable {
    param([string]$S)
    if ($null -eq $S) { return '' }
    $t = $S.Normalize([System.Text.NormalizationForm]::FormD)
    $sb = New-Object System.Text.StringBuilder
    foreach ($ch in $t.ToCharArray()) {
        if ([System.Globalization.CharUnicodeInfo]::GetUnicodeCategory($ch) -ne
            [System.Globalization.UnicodeCategory]::NonSpacingMark) {
            $null = $sb.Append($ch)
        }
    }
    $r = $sb.ToString().Normalize([System.Text.NormalizationForm]::FormC)
    return (($r -replace '\s+', ' ').Trim().ToUpperInvariant())
}

# ---------------------------------------------------------------------------
# 1. Party name map. Explicit so the mapping is auditable in a code review.
#    Legal-form suffixes (S.A. / S.A.R.L / R.L. / S.R.L) are preserved on
#    purpose: the form is real parsing input, the distinctive part is not.
#    Keys are written ASCII-only and matched via ConvertTo-Comparable.
# ---------------------------------------------------------------------------
$NameMap = [ordered]@{
    # --- natural persons ---
    'GABRIELA DE LOS ANGELES CASCANTE PEREZ'  = 'PERSONA FISICA PRUEBA UNO'
    'ALVARO ENRIQUE PADILLA FONSECA (MINISUPER LAS PALMERAS)' = 'PERSONA FISICA PRUEBA DOS MINI SUPER PRUEBA'
    'ALVARO ENRIQUE PADILLA FONSECA'   = 'PERSONA FISICA PRUEBA DOS'
    'ALVARO PADILLA FONSECA'            = 'PERSONA FISICA PRUEBA DOS'
    'PADILLA FONSECA ALVARO ENRIQUE'    = 'PERSONA FISICA PRUEBA DOS'
    'PADILLA FONSECA ALVARO'            = 'PERSONA FISICA PRUEBA DOS'
    '2493  ALVARO'                       = 'PERSONA FISICA PRUEBA DOS'
    # --- natural-person trade names ---
    'SUPER LAS PALMERAS'                = 'SUPER EL PRADO'
    'MINI SUPER Y LICORERA LAS PALMERAS' = 'MINI SUPER Y LICORERA EL PRADO'
    'LAS PALMERAS'                       = 'EL PRADO'
    # --- juridical persons ---
    'COOPERATIVA DE PRODUCTORES DE LECHE DOS PINOS R.L.' = 'COOPERATIVA DE PRODUCTORES DE LECHE DEL VALLE R.L.'
    'Distribuidora La Florida S.A.'      = 'Distribuidora La Prueba S.A.'
    'Distribuidora La Florida'            = 'Distribuidora La Prueba'
    'Philip Morris Costa Rica S.A.'      = 'Distribuidora Nacional de Tabacos S.A.'
    '3-102-830698 S.R.L'                 = 'Distribuidora Regional del Sur S.R.L.'
    'COMERCIAL DINANT DE COSTA RICA SOCIEDAD ANONIMA' = 'COMERCIAL DEL VALLE DE COSTA RICA SOCIEDAD ANONIMA'
    'Comercial Dinant de Costa Rica, S.A.'= 'Comercial del Valle de Costa Rica, S.A.'
    'Bimbo de Costa Rica S.A.'           = 'Panificadora del Sur S.A.'
    'COMPANIA DE GALLETAS POZUELO DCR, S.A.' = "COMPA$($script:NTILDE)IA DE GALLETAS DEL SUR DCR, S.A."
    'ALIMENTOS JACKS DE CENTROAMERICA, S.A.' = 'ALIMENTOS DEL NORTE CENTRAL, S.A.'
    'Kion de Costa Rica S.A.'            = 'Equipos Industriales del Sur S.A.'
    'PRODUCTOS KITTY S.A.'               = 'PRODUCTOS DEL VALLE S.A.'
    'PRODUCTOS KITTY SA'                 = 'PRODUCTOS DEL VALLE SA'
    'HILIX Y COMPANIA S.A.'              = "LUMINARIA Y COMPA$($script:NTILDE)IA DE PRUEBA S.A."
    'La Esfera Austral de la Pampa S.A.' = 'Comercial de la Pampa S.A.'
}

# comparable -> pseudonym, so diacritics/case/whitespace never block a match
$NameLookup = @{}
foreach ($k in $NameMap.Keys) { $NameLookup[(ConvertTo-Comparable $k)] = $NameMap[$k] }

# ---------------------------------------------------------------------------
# 2. Street addresses. Real, and one of them is a home address, so all are
#    replaced. Generics stay plausible for a Costa Rican commerce address and
#    respect XSD OtrasSenas bounds (minLength 5, maxLength 250).
# ---------------------------------------------------------------------------
$AddressMap = [ordered]@{
    '1, Canafistula'                                                       = 'CINCO CUADRAS AL NORTE DE LA PLAZA PRINCIPAL'
    '100 ESTE TAMARINDO DIRIA'                                           = 'CIENTO METROS AL ESTE DE LA CARRETERA PRINCIPAL'
    '100 NORTE DEL BN EN TAMARINDO  SANTA CRUZ'                          = 'CIENTO METROS AL NORTE DEL BANCO, RUTA INTERMEDIA'
    '100 NORTE HOTEL TAMARINDO DIRIA'                                    = 'CIENTO METROS AL NORTE DEL HOTEL, RUTA PRINCIPAL'
    '100 SUR DE PIZZA HUT TAMARINDO SANT'                                = 'CIENTO METROS AL SUR DEL CENTRO COMERCIAL, RUTA PRINCIPAL'
    '250 metros sur de la Planta Cerveza Llorente Flores, Heredia, Costa Rica Edificio Corporativo de FIFCO' = 'DOSCIENTOS CINCUENTA METROS AL SUR DE LA PLANTA, PROVINCIA DE PRUEBA'
    'AEROPUERTO JUAN SANTAMARIA, S/AUTOPISTA BERNARDO SOTO 7 KM AL OESTE, CONTIGUO ZONA FRANCA BES' = 'AEROPUERTO INTERNACIONAL, AUTOPISTA PRINCIPAL KM 7 AL OESTE'
    'CALLE 70 ENTRE AV. 39 Y 43, 300 M NORTE DEL PUENTE JUAN PABLO'      = 'CALLE 70 ENTRE AVENIDAS 39 Y 43, TRESCIENTOS METROS AL NORTE'
    'Cartago Cartago Llano Grande CARTAGO, LLANO GRANDE 2 KM AL NORTE DEL COLEGIO SER' = 'DISTRITO DE PRUEBA, DOS KILOMETROS AL NORTE DEL COLEGIO'
    'Complejo solarium bodega 5E'                                        = 'COMPLEJO COMERCIAL, BODEGA 5E, PABELLON INDUSTRIAL'
    'De la funeraria la Auxiliadora 300 Oeste izquierda apartamento 9b color blanco' = 'TRESCIENTOS METROS AL OESTE, EDIFICIO RESIDENCIAL, PISO 9B'
    'Del cruce de la Valencia 400 al este, Zona Industrial Zeta, Modulo 1 contig' = 'DEL CRUCE DE LA PRINCIPAL CUATROCIENTOS METROS AL ESTE, ZONA INDUSTRIAL, MODULO 1'
    'Heredia'                                                            = 'PROVINCIA DE PRUEBA, ZONA FRANCA, PARQUE INDUSTRIAL'
    'LIBERIA, GUANACASTE. FRENTE AL AEROPUERTO, COMPLEJO SOLARIUM'       = 'CIUDAD DE PRUEBA, FRENTE AL AEROPUERTO, COMPLEJO COMERCIAL'
    'San Jose Costa Rica'                                                = 'PROVINCIA DE PRUEBA, ZONA INDUSTRIAL CALLE 1 AVENIDA 3'
    'Santa Cruz'                                                         = 'DISTRITO DE PRUEBA, RUTA NACIONAL INTERMEDIA'
    'Santa Cruz, Tamarindo 100 N. Hotel Tamarindo Diria'                 = 'DISTRITO DE PRUEBA, CIENTO METROS AL NORTE DEL HOTEL'
    'TAMARINDO 100 NORTE HOTEL TAMARINDO DIRIA'                          = 'CIUDAD DE PRUEBA, CIENTO METROS AL NORTE DEL HOTEL, RUTA PRINCIPAL'
    'Tribute Corporate Center, Edif. Tributo B, Pisos 3 y 4'             = 'CENTRO CORPORATIVO, EDIFICIO TRIBUTO B, PISOS 3 Y 4'
}

# ---------------------------------------------------------------------------
# 3. Helper: build a shape-preserving synthetic identifier.
#    Keeps length and leading-digit class so prevalidation format branches
#    behave identically to the real value.
# ---------------------------------------------------------------------------
function New-SyntheticId {
    param([string]$Real, [int]$Index)
    $len = $Real.Length
    if ($len -lt 4) { throw "identifier too short to preserve shape: '$Real'" }
    # keep the first two real characters so the leading-digit class survives
    $head = $Real.Substring(0, 2)
    $tailLen = $len - $head.Length
    $fmt = '{0:D' + $tailLen + '}'
    $body = $fmt -f (100000 + $Index)
    $cand = $head + $body.Substring($body.Length - $tailLen)
    if ($cand.Length -ne $len) { throw "id length mismatch: '$Real' -> '$cand'" }
    if ($cand -eq $Real)         { throw "id collided with source: '$Real'" }
    return $cand
}

# ---------------------------------------------------------------------------
# 4. Pass 1 - read every source file, learn the identifier universe.
# ---------------------------------------------------------------------------
$sources = Get-ChildItem -LiteralPath $SourceDir -Filter *.xml | Sort-Object Name
if ($sources.Count -eq 0) { throw "no source invoices found in $SourceDir" }

$docs = @()
foreach ($f in $sources) {
    $raw = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $ns = if ($raw -match 'xmlns="([^"]+)"') { $Matches[1] } else { '' }
    $ver = if ($ns -match 'v(\d+\.\d+)') { $Matches[1] } else { '?' }
    $rootEl = if ($raw -match "<([A-Za-z]+)[\r\n][^>]*xmlns=") { $Matches[1] }
              elseif ($raw -match "<([A-Za-z]+)[ >]") { $Matches[1] } else { 'Factura' }
    $kind = if ($f.Name -match '-(FE|FC|FEC|NC|ND|TE|REP)-') { $Matches[1] }
            elseif ($rootEl -match 'NotaCredito') { 'NC' } else { 'FE' }
    $docs += [pscustomobject]@{
        File = $f; Raw = $raw; Version = $ver; Root = $rootEl; Kind = $kind
    }
}

# collect distinct identifiers so each maps to exactly one pseudonym
$rucSet   = [ordered]@{}
$mailSet  = [ordered]@{}
$phoneSet = [ordered]@{}
$claveSet = [ordered]@{}
foreach ($d in $docs) {
    foreach ($m in [regex]::Matches($d.Raw, '(?s)<Identificacion>\s*<Tipo>([^<]*)<.*?<Numero>([^<]*)<')) {
        $rucSet[$m.Groups[2].Value] = $m.Groups[1].Value
    }
    foreach ($m in [regex]::Matches($d.Raw, '<Correo(?:Electronico)?>([^<]+)<')) { $mailSet[$m.Groups[1].Value.ToLowerInvariant()] = 1 }
    foreach ($m in [regex]::Matches($d.Raw, '<NumTelefono>([^<]*)<')) {
        # one source invoice carries <NumTelefono>0</NumTelefono>; a bare '0' key
        # would turn Replace into "replace every zero byte in the document"
        if ($m.Groups[1].Value.Length -ge 6) { $phoneSet[$m.Groups[1].Value] = 1 }
    }
    foreach ($m in [regex]::Matches($d.Raw, '<Clave>(\d{50})<'))                   { $claveSet[$m.Groups[1].Value] = 1 }
}

$rucMap   = [ordered]@{}
$i = 0
foreach ($k in $rucSet.Keys) { $i++; $rucMap[$k] = New-SyntheticId -Real $k -Index $i }

# Pseudonym local parts are neutral on purpose: deriving them from the real
# local part leaked supplier brands (febimbocostarica -> "...bimbo...").
$mailMap = [ordered]@{}
$i = 0
foreach ($k in $mailSet.Keys) {
    $i++
    $mailMap[$k] = "facturacion.$i@proveedor-prueba.test"
}

$phoneMap = [ordered]@{}
$i = 0
foreach ($k in $phoneSet.Keys) {
    $i++
    $lead = if ($k.Length -gt 0) { $k.Substring(0,1) } else { '2' }
    $n = 2 + ($i * 1111)
    $phoneMap[$k] = "$lead$('{0:D7}' -f ($n % 10000000))"
}

Write-Host "learned $($rucSet.Count) tax ids, $($mailSet.Count) emails, $($phoneSet.Count) phones, $($claveSet.Count) claves"

# ---------------------------------------------------------------------------
# 5. Clave rebuild. Real layout (verified against 4 source invoices):
#       [0..2]   506              country
#       [3..8]   YYMMDD           issue date
#       [9..20]  RUC zero-padded  12  <-- embeds the real tax id
#       [21..40] consecutivo      20
#       [41..49] security         9
#    3 + 6 + 12 + 20 + 9 = 50
# ---------------------------------------------------------------------------
function Get-YymmddFromRaw { param([string]$Raw)
    if ($Raw -match '<FechaEmision[^>]*>(\d{4})-(\d{2})-(\d{2})') {
        return $Matches[1].Substring(2) + $Matches[2] + $Matches[3]
    }
    return '250101'
}

# Clave layout, verified against 4 source invoices:
#   [0..2] 506 country | [3..8] YYMMDD | [9..20] RUC zero-padded to 12
#   [21..40] consecutivo (20) | [41..49] security (9)   = 50 digits
function New-Clave {
    param([string]$Yymmdd, [string]$Ruc, [string]$Consecutivo, [int]$Salt)
    $segRuc = $Ruc.PadLeft(12, '0')
    if ($segRuc.Length -gt 12) { $segRuc = $segRuc.Substring($segRuc.Length - 12) }
    $segSec = '{0:D9}' -f (100000 + $Salt)
    $c = '506' + $Yymmdd + $segRuc + $Consecutivo + $segSec
    if ($c.Length -ne 50) {
        throw ("Clave length $($c.Length) != 50`n" +
               "  Yymmdd      = '$Yymmdd' (len $($Yymmdd.Length))`n" +
               "  Ruc         = '$Ruc' (len $($Ruc.Length))`n" +
               "  Consecutivo = '$Consecutivo' (len $($Consecutivo.Length))`n" +
               "  segRuc      = '$segRuc' (len $($segRuc.Length))`n" +
               "  segSec      = '$segSec' (len $($segSec.Length))")
    }
    return $c
}

# ---------------------------------------------------------------------------
# 6. Transform helpers
# ---------------------------------------------------------------------------
function Remove-Signature {
    param([string]$Xml)
    $x = [regex]::Replace($Xml, '<\?xml-stylesheet[^>]*\?>', '')
    # ds:Signature contains no nested ds:Signature, so a lazy match is safe
    $x = [regex]::Replace($x, '(?s)<ds:Signature\b.*?</ds:Signature>', '')
    # the ds/dsig prefixes are declared on the root and are now unused; drop
    # the declarations so fixtures do not carry a dangling xmldsig namespace
    if (-not $x.Contains('<ds:')) {
        $x = [regex]::Replace($x, '\s+xmlns:dsig="[^"]*"', '')
        $x = [regex]::Replace($x, '\s+xmlns:ds="[^"]*"', '')
        $x = [regex]::Replace($x, '\s+xmlns:xades="[^"]*"', '')
    }
    return $x
}

function Get-FieldValue { param([string]$Block, [string]$Tag)
    if ($Block -match "<$Tag>([^<]*)<") { return $Matches[1] }
    return $null
}

function Set-PartyBlock {
    param([string]$Block, [hashtable]$Lookup)
    $out = $Block
    foreach ($tag in @('Nombre', 'NombreComercial')) {
        $v = Get-FieldValue $Block $tag
        if ($null -eq $v) { continue }
        $pseudo = $Lookup[(ConvertTo-Comparable $v)]
        if ($null -eq $pseudo) {
            $null = $script:UnmappedNames.Add("$tag = '$v'")
            continue
        }
        $out = [regex]::Replace(
            $out,
            "(?<=<$tag>)$([regex]::Escape($v))(?=</$tag>)",
            [System.Text.RegularExpressions.MatchEvaluator]{ param($m) $pseudo })
    }
    return $out
}

$script:UnmappedNames = New-Object System.Collections.ArrayList
$script:UnmappedAddresses = New-Object System.Collections.ArrayList
$script:UnmappedMails = New-Object System.Collections.ArrayList
$script:UnmappedPhones = New-Object System.Collections.ArrayList

# The address alternation must be built from the values that actually occur in
# the source documents, not from the map keys: the keys are ASCII (the script
# is read as ANSI without a BOM) while the documents carry diacritics, e.g.
# "1, Cañafístula". Matching the real text first and resolving through
# ConvertTo-Comparable is what lets the ASCII key find the accented value.
$realAddresses = [ordered]@{}
foreach ($d in $docs) {
    foreach ($m in [regex]::Matches($d.Raw, '<OtrasSenas>([^<]*)</OtrasSenas>')) {
        $realAddresses[$m.Groups[1].Value] = 1
    }
}
# comparable -> pseudonym for addresses, same rationale as $NameLookup
$AddressLookup = @{}
foreach ($k in $AddressMap.Keys) { $AddressLookup[(ConvertTo-Comparable $k)] = $AddressMap[$k] }
# longest-first alternation so a short address cannot shadow a longer one
$AddressAlternation = ($realAddresses.Keys | Sort-Object Length -Descending |
    ForEach-Object { [regex]::Escape($_) }) -join '|'
Write-Host "harvested $($realAddresses.Count) distinct address strings from sources"

# ---------------------------------------------------------------------------
# ResumenFactura/Otros is a producer extension block keyed by a @codigo
# attribute, and it carries real identifiers that no element-level rule would
# catch: internal customer codes, the issuer's own geography and order
# references. Handle it by attribute, not by value.
# ---------------------------------------------------------------------------
$OtrosGeography = @(
    'emisor_provincia', 'emisor_canton', 'emisor_distrito', 'emisor_barrio',
    'receptor_provincia', 'receptor_canton', 'receptor_distrito', 'receptor_barrio'
)
$OtrosCodes = @('CodigoInternoDeCliente', 'Codigo_cliente', 'Numero_de_Referencia', 'NumeroOrden')
$OtrosGeographyValue = @{
    'provincia' = 'PROVINCIA DE PRUEBA'
    'canton'    = 'CANTON DE PRUEBA'
    'distrito'  = 'DISTRITO DE PRUEBA'
    'barrio'    = 'BARRIO DE PRUEBA'
}

# replace every digit run with a different digit run of the same length, so a
# customer code or order number keeps its shape without keeping its value
function New-SyntheticDigits {
    param([string]$Value, [int]$Salt)
    return [regex]::Replace($Value, '\d+', {
        param($m)
        $digits = $m.Value
        $out = ''
        foreach ($ch in $digits.ToCharArray()) {
            $out += [char](48 + (([int]$ch - 48 + $Salt + 3) % 10))
        }
        return $out
    })
}

function Convert-Otros {
    param([string]$Xml)
    $rx = New-Object System.Text.RegularExpressions.Regex(
        '(?<=<OtroTexto(?: codigo="(?<c>[^"]*)")?>)(?<v>[^<]*)(?=</OtroTexto>)')
    $salt = 0
    return $rx.Replace($Xml, [System.Text.RegularExpressions.MatchEvaluator]{
        param($m)
        $salt++
        $code = $m.Groups['c'].Value
        $val  = $m.Groups['v'].Value
        if ([string]::IsNullOrWhiteSpace($val)) { return $val }

        if ($OtrosGeography -contains $code) {
            $key = ($code -split '_')[-1]
            return $OtrosGeographyValue[$key]
        }
        if ($OtrosCodes -contains $code) {
            return New-SyntheticDigits -Value $val -Salt $salt
        }
        if ($code -eq 'DireccionSucursal') {
            # 'provincia|canton|distrito|barrio|address' - keep the numeric
            # prefix (geographic codes, not identifiers) and map the address
            $parts = $val -split '\|'
            if ($parts.Count -ge 5) {
                $lookup = $AddressLookup[(ConvertTo-Comparable $parts[-1])]
                if ($null -ne $lookup) {
                    $parts[-1] = $lookup
                    return ($parts -join '|')
                }
            }
            return $val
        }
        if ([string]::IsNullOrEmpty($code) -and $val -match '\d') {
            # unattributed free text that still carries a number, e.g.
            # "Basado en Pedidos de cliente 482036."
            return New-SyntheticDigits -Value $val -Salt $salt
        }
        return $val
    })
}

Write-Host "transform pass..."
# ---------------------------------------------------------------------------
# 7. Per-document transform
# ---------------------------------------------------------------------------
$manifest = @()
$seq = 0
$unmapped = New-Object System.Collections.ArrayList

foreach ($d in $docs) {
    $seq++
    $xml = Remove-Signature $d.Raw

    $emisorRuc = $null; $receptorRuc = $null
    if ($xml -match '(?s)<Emisor>(.*?)</Emisor>')   { $eb = $Matches[1] }
    if ($xml -match '(?s)<Receptor>(.*?)</Receptor>') { $rb = $Matches[1] }
    $eb = if ($null -ne $eb) { $eb } else { '' }
    $rb = if ($null -ne $rb) { $rb } else { '' }

    $eId = Get-FieldValue $eb 'Numero'
    $rId = Get-FieldValue $rb 'Numero'
    $eTipo = Get-FieldValue $eb 'Tipo'
    $rTipo = Get-FieldValue $rb 'Tipo'

    # Identificacion/Numero -> synthetic
    if ($eId) { $emisorRuc = $rucMap[$eId]; $script:realRucs = $eId }
    if ($rId) { $receptorRuc = $rucMap[$rId]; $script:realRucs = $rId }

    # --- party blocks ---
    $newEb = Set-PartyBlock $eb $NameLookup
    $newRb = Set-PartyBlock $rb $NameLookup
    if ($newEb -ne $eb -and $xml.Contains($eb)) { $xml = $xml.Replace($eb, $newEb) }
    if ($newRb -ne $rb -and $xml.Contains($rb)) { $xml = $xml.Replace($rb, $newRb) }

    # --- tax ids (both parties, all occurrences) ---
    foreach ($k in $rucMap.Keys) {
        if ($xml.Contains($k)) { $xml = $xml.Replace("<Numero>$k</Numero>", "<Numero>$($rucMap[$k])</Numero>") }
    }

    # --- emails: case-insensitive sweep, because producers emit the same
    #     address as both altamara@ice.co.cr and ALTAMARA@ICE.CO.CR and a
    #     case-sensitive replace silently leaves the uppercase form behind ---
    $mailRx = New-Object System.Text.RegularExpressions.Regex(
        '(?<=<Correo(?:Electronico)?>)([^<]+)(?=</)',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $xml = $mailRx.Replace($xml, [System.Text.RegularExpressions.MatchEvaluator]{
        param($m)
        $lookup = $mailMap[$m.Groups[1].Value.ToLowerInvariant()]
        if ($null -eq $lookup) {
            $null = $script:UnmappedMails.Add($m.Groups[1].Value)
            return $m.Groups[1].Value
        }
        return $lookup
    })

    # --- phones: scoped to the element, and only for values long enough to be
    #     a real phone number. A global Replace would also hit digit runs that
    #     merely contain the same sequence. ---
    $phoneRx = [regex]'(?<=<NumTelefono>)([^<]*)(?=</NumTelefono>)'
    $xml = $phoneRx.Replace($xml, [System.Text.RegularExpressions.MatchEvaluator]{
        param($m)
        $lookup = $phoneMap[$m.Groups[1].Value.Trim()]
        if ($null -eq $lookup) {
            if ($m.Groups[1].Value.Trim().Length -ge 6) {
                $null = $script:UnmappedPhones.Add($m.Groups[1].Value)
            }
            return $m.Groups[1].Value
        }
        return $lookup
    })

    # --- addresses: global sweep over every real address, longest first.
    #     Scoped per-element it missed <Otros><OtroTexto codigo="DireccionSucursal">
    #     inside ResumenFactura, which carries a branch address in free text. ---
    $addrRx = New-Object System.Text.RegularExpressions.Regex(
        $AddressAlternation,
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase)
    $xml = $addrRx.Replace($xml, [System.Text.RegularExpressions.MatchEvaluator]{
        param($m)
        $lookup = $AddressLookup[(ConvertTo-Comparable $m.Value)]
        if ($null -eq $lookup) {
            $null = $script:UnmappedAddresses.Add($m.Value)
            return $m.Value
        }
        return $lookup
    })
    # Barrio is left alone here: v4.3 types it as PositiveInteger ('01'..'36')
    # and only v4.4 imposes minLength 5, so it is widened during derivation.

    # --- NumeroConsecutivo: keep the real shape, make the tail unique ---
    $consec = Get-FieldValue $xml 'NumeroConsecutivo'
    $newConsec = $consec
    if ($consec -and $consec.Length -eq 20) {
        $newConsec = $consec.Substring(0, 12) + ('{0:D8}' -f (700000 + $seq))
    }

    # --- Otros extension block: keyed on @codigo, see Convert-Otros ---
    $xml = Convert-Otros $xml

    # --- Clave, rebuilt in three ordered steps ---
    #
    # A Clave is rebuilt rather than pattern-matched because a real one embeds
    # the real RUC in digits 10-21. The sweep is anchored to a full element
    # value starting at the country code 506; a bare \d{50} would also match
    # unrelated 50-digit runs and corrupt them.
    $yymmdd = Get-YymmddFromRaw $xml
    $rucForClave = if ($emisorRuc) { $emisorRuc } else { '3000000000' }

    # 1. unique consecutivo first, so the rebuilt Clave agrees with it
    if ($consec) {
        $xml = $xml.Replace("<NumeroConsecutivo>$consec</NumeroConsecutivo>",
                            "<NumeroConsecutivo>$newConsec</NumeroConsecutivo>")
    }
    $newClave = New-Clave -Yymmdd $yymmdd -Ruc $rucForClave -Consecutivo $newConsec -Salt $seq

    # 2. the document's own Clave
    $ownClave = New-Object System.Text.RegularExpressions.Regex('(?<=<Clave>)(506\d{47})(?=</Clave>)')
    $xml = $ownClave.Replace($xml, $newClave)

    # 3. Claves cited elsewhere. InformacionReferencia/Numero carries the Clave
    #    of an invoice that is not itself in the source set, so a list of known
    #    Claves misses it; matching the shape catches every reference. Date and
    #    consecutivo are preserved, only the embedded RUC is swapped.
    $refClave = New-Object System.Text.RegularExpressions.Regex('(?<=<[A-Za-z][A-Za-z0-9]*>)(506\d{47})(?=</)')
    $refSeen = @{}
    $xml = $refClave.Replace($xml, [System.Text.RegularExpressions.MatchEvaluator]{
        param($m)
        $c = $m.Value
        if ($refSeen.ContainsKey($c)) { return $refSeen[$c] }
        $embRuc = $c.Substring(9, 12).TrimStart('0')
        if ([string]::IsNullOrEmpty($embRuc)) { $embRuc = '3000000000' }
        $map = $rucMap[$embRuc]
        $useRuc = if ($map) { $map } else { New-SyntheticId -Real $embRuc -Index 900 }
        # stable salt from the Clave itself, so reruns are deterministic
        $salt = [Math]::Abs($c.GetHashCode() % 9000) + 1000
        $rebuilt = New-Clave -Yymmdd $c.Substring(3, 6) -Ruc $useRuc -Consecutivo $c.Substring(21, 20) -Salt $salt
        $refSeen[$c] = $rebuilt
        return $rebuilt
    })

    # ProveedorSistemas is required in v4.4 and absent in v4.3. It is added
    # during the v4.4 derivation, never here: the official v4.3 schema rejects it.

    $outName = "{0}-v43-{1:D2}.xml" -f $d.Kind.ToLower(), $seq
    [System.IO.File]::WriteAllText((Join-Path $V43Dir $outName), $xml.Trim() + "`n", (New-Object System.Text.UTF8Encoding($false)))

    $manifest += [pscustomobject]@{
        fixture     = $outName
        kind        = $d.Kind
        sourceKind  = $d.Kind
        sourceFile  = $d.File.Name
        emisorRuc   = $emisorRuc
        receptorRuc = $receptorRuc
        consecutive = $newConsec
    }
}

Write-Host "wrote $($manifest.Count) v4.3 fixtures"
$manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath (Join-Path $OutRoot "manifest-v43.json") -Encoding UTF8

# dump the maps so the run is auditable
@{
    names   = $NameMap
    address = $AddressMap
    ruc     = $rucMap
    mail    = $mailMap
    phone   = $phoneMap
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $OutRoot "ANONYMIZATION-MAP.json") -Encoding UTF8

Write-Host "done."

# unmapped values are the real risk: anything not in the map is left untouched
if ($script:UnmappedNames.Count -gt 0) {
    Write-Warning "UNMAPPED party names (left as-is, will fail the leak scan):"
    $script:UnmappedNames | Sort-Object -Unique | ForEach-Object { Write-Warning "   $_" }
} else { Write-Host "all party names mapped" }
if ($script:UnmappedAddresses.Count -gt 0) {
    Write-Warning "UNMAPPED addresses (left as-is, will fail the leak scan):"
    $script:UnmappedAddresses | Sort-Object -Unique | ForEach-Object { Write-Warning "   $_" }
} else { Write-Host "all addresses mapped" }
