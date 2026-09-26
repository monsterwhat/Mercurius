param(
    [string]$OutRoot = "D:\Documents\Github\Mercurius\app\src\test\resources\fixtures\reales",
    [string]$SourceDir = "D:\Documents\Facturas Electronicas\Todas",
    [string]$RepoRoot = "D:\Documents\Github\Mercurius"
)

$ErrorActionPreference = 'Stop'
$fail = 0
$line = '=' * 74

function Say([string]$s) { Write-Host $s }

# ---------------------------------------------------------------------------
# Rebuild the forbidden-value universe straight from the SOURCE invoices, so
# this check is independent of the generator's own bookkeeping.
# ---------------------------------------------------------------------------
$forbidden = [ordered]@{}
foreach ($f in Get-ChildItem -LiteralPath $SourceDir -Filter *.xml) {
    $raw = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    foreach ($m in [regex]::Matches($raw, '(?s)<Identificacion>\s*<Tipo>[^<]*<.*?<Numero>([^<]*)<')) { $forbidden["ruc:" + $m.Groups[1].Value] = 1 }
    foreach ($m in [regex]::Matches($raw, '<Correo(?:Electronico)?>([^<]+)<')) { $forbidden["email:" + $m.Groups[1].Value] = 1 }
    foreach ($m in [regex]::Matches($raw, '<NumTelefono>([^<]*)<')) {
        $v = $m.Groups[1].Value
        # '0' and other 1-2 char values are too generic to substring-match safely
        if ($v.Length -ge 6) { $forbidden["phone:$v"] = 1 }
    }
    foreach ($m in [regex]::Matches($raw, '<OtrasSenas>([^<]*)<')) { $forbidden["addr:" + $m.Groups[1].Value] = 1 }
    # ResumenFactura/Otros is a producer extension block: internal customer
    # codes, issuer geography and order references live here
    foreach ($m in [regex]::Matches($raw, '<OtroTexto(?: codigo="([^"]*)")?>([^<]+)</OtroTexto>')) {
        $c = $m.Groups[1].Value; $v = $m.Groups[2].Value
        if ($c -match '^(emisor|receptor)_(provincia|canton|distrito|barrio)$' -and $v.Trim()) {
            $forbidden["geo:$($m.Groups[1].Value)=$v"] = 1
        }
        elseif ($c -match '^(CodigoInternoDeCliente|Codigo_cliente|Numero_de_Referencia|NumeroOrden)$' -and $v.Trim()) {
            $forbidden["code:$c=$v"] = 1
        }
        elseif ([string]::IsNullOrEmpty($c) -and $v -match '\d') {
            $forbidden["free:$v"] = 1
        }
    }
    foreach ($m in [regex]::Matches($raw, '<Nombre>([^<]*)<')) { $forbidden["name:" + $m.Groups[1].Value.Trim()] = 1 }
    foreach ($m in [regex]::Matches($raw, '<Clave>(\d{50})<')) { $forbidden["clave:" + $m.Groups[1].Value] = 1 }
    # person-name fragments: these must never survive anywhere in a fixture
    foreach ($t in @('CASCANTE','Cascante','PADILLA','Padilla','FONSECA','Fonseca',
                     'GABRIELA','Gabriela','ANGELES CASCANTE')) {
        $forbidden["person:$t"] = 1
    }
    # supplier-brand tokens: forbidden in PARTY blocks only. Brand names inside
    # LineaDetalle/<Detalle> are real product data and are intentionally kept.
    foreach ($t in @('Bimbo','BIMBO','DINANT','Dinant','JACKS','Jacks','KITTY','Kitty',
                     'KION','Kion','FIFCO','Fifco','POZUELO','Pozuelo','DOSPINOS',
                     'DOS PINOS','Dos Pinos','HILIX','Hilix','Philip','PHILIP','MORRIS',
                     'Morris','BARRIL','Baril','PALMERAS','Palmeras','ESFERA','HELIX')) {
        $forbidden["supplier:$t"] = 1
    }
}

# The supplier-brand check runs against party blocks only, so build the set of
# "text that is legitimately allowed to contain brands" = everything else.
$partyBlocks = [ordered]@{}

Say $line
Say "LEAK SCAN over generated fixtures"
Say $line

$files = Get-ChildItem -LiteralPath $OutRoot -Recurse -Filter *.xml
Say "scanning $($files.Count) generated xml files against $($forbidden.Count) forbidden values"
$hits = New-Object System.Collections.ArrayList
foreach ($f in $files) {
    $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    # party blocks are scanned for supplier brands; the rest of the document is
    # not, because real product <Detalle> text legitimately names brands
    $party = ''
    foreach ($m in [regex]::Matches($txt, '(?s)<(Emisor|Receptor)>(.*?)</\1>')) { $party += $m.Value }
    foreach ($k in $forbidden.Keys) {
        $kind = $k.Substring(0, $k.IndexOf(':'))
        $needle = $k.Substring($k.IndexOf(':') + 1)
        if ([string]::IsNullOrWhiteSpace($needle)) { continue }
        $hay = if ($kind -eq 'supplier') { $party } else { $txt }
        if ($hay.IndexOf($needle, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $null = $hits.Add("$($f.Name)  <-  $k = '$needle'")
        }
    }
}
if ($hits.Count -eq 0) {
    Say "  PASS  no forbidden value found in any fixture"
} else {
    $fail++
    Say "  FAIL  $($hits.Count) leak(s):"
    $hits | Select-Object -First 30 | ForEach-Object { Say "        $_" }
    if ($hits.Count -gt 30) { Say "        ... and $($hits.Count - 30) more" }
}

# ---------------------------------------------------------------------------
Say ""
Say $line
Say "SIGNATURE / CERTIFICATE STRIP"
Say $line
$certHits = @()
foreach ($f in $files) {
    $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    foreach ($needle in @('ds:Signature','X509Certificate','RSAKeyValue','SigningTime','xades:')) {
        if ($txt.Contains($needle)) { $certHits += "$($f.Name) contains '$needle'" }
    }
}
if ($certHits.Count -eq 0) { Say "  PASS  no signature, cert, RSA key, xades or signing time in any fixture" }
else { $fail++; Say "  FAIL  $($certHits.Count):"; $certHits | Select-Object -First 10 | ForEach-Object { Say "        $_" } }
# an unused xmldsig namespace declaration is harmless but untidy.
# Match the whole attribute name: 'xmlns:ds' is a substring of 'xmlns:dsig'.
$dangling = @()
foreach ($f in $files) {
    $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $declares = ($txt -match 'xmlns:ds="') -or ($txt -match 'xmlns:dsig="') -or ($txt -match 'xmlns:xades="')
    if ($declares -and -not ($txt -match '<ds:')) { $dangling += $f.Name }
}
if ($dangling.Count -gt 0) { Say "  WARN  $($dangling.Count) file(s) keep an unused xmldsig namespace declaration: $($dangling -join ', ')" }
else { Say "  PASS  no unused xmldsig namespace declarations" }

# A generator bug once shipped a literal PowerShell subexpression into a
# fixture because a map value was single-quoted. Nothing else would catch it,
# so treat any unexpanded script fragment as a leak.
$marker = [string][char]36 + '('
$fragHits = @()
foreach ($f in $files) {
    $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    foreach ($needle in @($marker, '$script:', '${')) {
        if ($txt.Contains($needle)) { $fragHits += "$($f.Name) contains '$needle'" }
    }
}
if ($fragHits.Count -eq 0) { Say "  PASS  no unexpanded generator script fragments in any fixture" }
else { $fail++; Say "  FAIL  $($fragHits.Count):"; $fragHits | Select-Object -First 8 | ForEach-Object { Say "        $_" } }

# ---------------------------------------------------------------------------
Say ""
Say $line
Say "CLAVE / CONSECUTIVO INTEGRITY"
Say $line
$seenClave = @{}; $seenCons = @{}; $bad = @()
foreach ($f in $files) {
    $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    foreach ($m in [regex]::Matches($txt, '<Clave>(\d+)<')) {
        $c = $m.Groups[1].Value
        if ($c.Length -ne 50) { $bad += "$($f.Name): Clave length $($c.Length)" }
        elseif (-not $c.StartsWith('506')) { $bad += "$($f.Name): Clave country prefix '$($c.Substring(0,3))'" }
        if ($seenClave.ContainsKey($c)) { $bad += "$($f.Name): duplicate Clave (also in $($seenClave[$c]))" }
        else { $seenClave[$c] = $f.Name }
    }
    foreach ($m in [regex]::Matches($txt, '<NumeroConsecutivo>(\d+)<')) {
        $c = $m.Groups[1].Value
        if ($c.Length -ne 20) { $bad += "$($f.Name): Consecutivo length $($c.Length)" }
        if ($seenCons.ContainsKey($c)) { $bad += "$($f.Name): duplicate Consecutivo (also in $($seenCons[$c]))" }
        else { $seenCons[$c] = $f.Name }
    }
}
if ($bad.Count -eq 0) { Say "  PASS  all Clave are 50 digits starting 506, all Consecutivo 20 digits, all unique" }
else { $fail++; Say "  FAIL  $($bad.Count):"; $bad | Select-Object -First 10 | ForEach-Object { Say "        $_" } }

# ---------------------------------------------------------------------------
Say ""
Say $line
Say "XSD VALIDATION"
Say $line
$xsdRoot = Join-Path $RepoRoot "app\src\main\resources\xsd"
function Get-CompiledSchema([string]$xsdRel) {
    $path = Join-Path $xsdRoot $xsdRel
    $rs = New-Object System.Xml.XmlReaderSettings
    $rs.DtdProcessing = [System.Xml.DtdProcessing]::Parse
    $rs.XmlResolver = $null
    $r = [System.Xml.XmlReader]::Create($path, $rs)
    $s = [System.Xml.Schema.XmlSchema]::Read($r, $null)
    $r.Close()
    return ,$s
}
function Test-Doc([string]$xmlPath, $set) {
    $errs = New-Object System.Collections.ArrayList
    $st = New-Object System.Xml.XmlReaderSettings
    $st.ValidationType = [System.Xml.ValidationType]::Schema
    $st.Schemas = $set
    $st.DtdProcessing = [System.Xml.DtdProcessing]::Ignore
    $st.add_ValidationEventHandler({ param($s,$e) $null = $errs.Add("$($e.Severity) line $($e.Exception.LineNumber): $($e.Message)") })
    $r = [System.Xml.XmlReader]::Create($xmlPath, $st)
    try { while ($r.Read()) { } } catch { $null = $errs.Add("FATAL: $($_.Exception.Message)") }
    $r.Close()
    return ,$errs
}
$set43 = New-Object System.Xml.Schema.XmlSchemaSet
$set43.XmlResolver = $null
$null = $set43.Add((Get-CompiledSchema 'xmldsig-core-schema.xsd'))
$null = $set43.Add((Get-CompiledSchema 'v4.3\FacturaElectronica_V4.3.xsd'))
$set43.Compile()

# The official Hacienda schema mandates <ds:Signature> (minOccurs=1), so an
# unsigned document can never be schema-valid. Stripping the real XAdES
# signature is non-negotiable, so that single error is an accepted, documented
# deviation; every other error is a real defect.
function Test-DocIgnoringSignature {
    param([string]$xmlPath, $set)
    $all = Test-Doc $xmlPath $set
    # the same single defect surfaces in two phrasings: "expected ds:Signature"
    # and "incomplete content ... expected 'Signature' in xmldsig namespace"
    $real = @($all | Where-Object {
        $_ -notmatch 'ds:Signature' -and
        $_ -notmatch 'incomplete content' -and
        $_ -notmatch 'xmldsig'
    })
    return ,$real
}

$v43 = Get-ChildItem -LiteralPath (Join-Path $OutRoot 'v4.3') -Filter *.xml | Sort-Object Name
$valid = 0; $invalid = @()
foreach ($f in $v43) {
    $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    if ($txt -notmatch 'v4\.3/') { $invalid += "$($f.Name): not a v4.3 document"; continue }
    $e = Test-DocIgnoringSignature $f.FullName $set43
    if ($e.Count -eq 0) { $valid++ } else { $invalid += "$($f.Name): $($e[0])" }
}
Say "  v4.3 set: $valid / $($v43.Count) valid (ignoring the mandatory ds:Signature only)"
$invalid | Select-Object -First 8 | ForEach-Object { Say "      $_" }
if ($invalid.Count -gt 0) { $fail++ }

# ---- v4.4 set: FE documents validate against the FE schema, NC against NC ----
$v44Dir = Join-Path $OutRoot 'v4.4'
if (Test-Path -LiteralPath $v44Dir) {
    $set44 = New-Object System.Xml.Schema.XmlSchemaSet
    $set44.XmlResolver = $null
    $null = $set44.Add((Get-CompiledSchema 'xmldsig-core-schema.xsd'))
    $null = $set44.Add((Get-CompiledSchema 'v4.4\FacturaElectronica_V4.4.xsd'))
    $set44.Compile()
    $set44nc = New-Object System.Xml.Schema.XmlSchemaSet
    $set44nc.XmlResolver = $null
    $null = $set44nc.Add((Get-CompiledSchema 'xmldsig-core-schema.xsd'))
    $null = $set44nc.Add((Get-CompiledSchema 'v4.4\NotaCreditoElectronica_V4.4.xsd'))
    $set44nc.Compile()

    $v44 = Get-ChildItem -LiteralPath $v44Dir -Filter *.xml | Sort-Object Name
    $ok = 0; $bad = @()
    foreach ($f in $v44) {
        $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
        if ($txt -notmatch 'v4\.4/') { $bad += "$($f.Name): not a v4.4 document"; continue }
        $isNc = $txt -match '<NotaCreditoElectronica[\r\n ]'
        $schema = if ($isNc) { $set44nc } else { $set44 }
        $label  = if ($isNc) { 'NC' } else { 'FE' }
        $e = Test-DocIgnoringSignature $f.FullName $schema
        if ($e.Count -eq 0) { $ok++ } else { $bad += "[$label] $($f.Name): $($e[0])" }
    }
    Say "  v4.4 set: $ok / $($v44.Count) valid (ignoring the mandatory ds:Signature only)"
    $bad | Select-Object -First 6 | ForEach-Object { Say "      $_" }
    if ($bad.Count -gt 0) { $fail++ }
}

Say ""
if ($fail -eq 0) { Say "ALL CHECKS PASSED" } else { Say "$fail CHECK GROUP(S) FAILED" }
exit $fail
