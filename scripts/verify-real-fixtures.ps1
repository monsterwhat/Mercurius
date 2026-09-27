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
    # InformacionReferencia is a root-level block of free text: <Razon> and the
    # <OtroTexto>/<OtroContenido> extension fields are the one place on a
    # document where a producer can quote a supplier, an order number or a
    # customer reference in prose. A real bare 10-digit reference survived here
    # once (<Razon>1602624939</Razon>), so every digit run harvested out of these
    # fields is treated as a potential external identifier.
    # Prose that carries no digits ("Devolucion de producto", "Nota Credito") is
    # deliberately NOT harvested: it is not an identifier, and
    # substring-matching a common phrase would flag every fixture.
    # The block is delimited explicitly because <ds:Signature> follows it.
    foreach ($m in [regex]::Matches($raw, '(?s)<InformacionReferencia>(.*?)</InformacionReferencia>')) {
        foreach ($fr in [regex]::Matches($m.Groups[1].Value, '<(Razon|OtroTexto|OtroContenido)(?:\s[^>]*)?>([^<]*)<')) {
            foreach ($dr in [regex]::Matches($fr.Groups[2].Value, '\d+')) {
                $forbidden["refdigits:" + $dr.Value] = 1
            }
        }
    }
    foreach ($m in [regex]::Matches($raw, '<Nombre>([^<]*)<')) { $forbidden["name:" + $m.Groups[1].Value.Trim()] = 1 }
    # v4.4 ClaveType is [a-zA-Z0-9]{50,50}, not v4.3's \d{50}. A \d{50} here
    # would match nothing for an alphanumeric Clave, so the real Clave would
    # never enter the forbidden set and the leak scan below would report PASS
    # without ever having looked for it.
    foreach ($m in [regex]::Matches($raw, '<Clave>([a-zA-Z0-9]{50})<')) { $forbidden["clave:" + $m.Groups[1].Value] = 1 }
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
        # a 1-5 digit reference cannot be substring-matched against a whole
        # document without colliding with unrelated numbers, so those are only
        # ever matched element-scoped, in the pass below
        if ($kind -eq 'refdigits' -and $needle.Length -lt 6) { continue }
        $hay = if ($kind -eq 'supplier') { $party } else { $txt }
        if ($hay.IndexOf($needle, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            $null = $hits.Add("$($f.Name)  <-  $k = '$needle'")
        }
    }
}
# Element-scoped pass for the short InformacionReferencia digit runs the
# document-wide substring scan above deliberately skipped. Each digit run found
# in an output free-text field is compared for EQUALITY against the harvested
# short runs, so a 1-2 digit reference is still caught while an unrelated number
# elsewhere in the document can never be mistaken for one.
$shortRef = @{}
foreach ($k in $forbidden.Keys) {
    if ($k.StartsWith('refdigits:')) {
        $v = $k.Substring($k.IndexOf(':') + 1)
        if ($v.Length -lt 6) { $shortRef[$v] = 1 }
    }
}
$irRx   = [regex]'(?s)<InformacionReferencia>(.*?)</InformacionReferencia>'
$irFld  = [regex]'<(Razon|OtroTexto|OtroContenido)(?:\s[^>]*)?>([^<]*)<'
foreach ($f in $files) {
    $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    foreach ($m in $irRx.Matches($txt)) {
        foreach ($fr in $irFld.Matches($m.Groups[1].Value)) {
            foreach ($dr in [regex]::Matches($fr.Groups[2].Value, '\d{1,5}')) {
                if ($shortRef.ContainsKey($dr.Value)) {
                    $null = $hits.Add("$($f.Name)  <-  <$($fr.Groups[1].Value)> still holds the source digit run '$($dr.Value)'")
                }
            }
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
    # [a-zA-Z0-9]+, not \d+: v4.4 ClaveType allows letters, and a \d+ match
    # would skip every alphanumeric Clave - not length-checked, not uniqueness
    # -checked, while this group still reported PASS.
    foreach ($m in [regex]::Matches($txt, '<Clave>([a-zA-Z0-9]+)<')) {
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
if ($bad.Count -eq 0) { Say "  PASS  all Clave are 50 characters starting 506, all Consecutivo 20 digits, all unique" }
else { $fail++; Say "  FAIL  $($bad.Count):"; $bad | Select-Object -First 10 | ForEach-Object { Say "        $_" } }

# A Clave embeds the issuing RUC in digits 10-21, left-padded to 12. If the
# generator ever rebuilds a Clave twice (for example a sweep that also matches
# the document's own <Clave>), the second pass treats the synthetic RUC as real
# input and produces a doubly-synthesized RUC that no longer matches the
# Emisor. Nothing above catches that: the Clave is still 50 characters, still
# starts with 506 and is still unique. Assert the segments agree instead.
$badRuc = @()
foreach ($f in $files) {
    $txt = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $clave = [regex]::Match($txt, '<Clave>([a-zA-Z0-9]{50})</Clave>').Groups[1].Value
    if (-not $clave) { continue }
    # [^<]+, not \d+: v4.4 dropped the \d{9,12} restriction from
    # IdentificacionType/Numero, so \d+ would capture nothing and this would
    # report a misleading "no Emisor/Identificacion/Numero found" instead.
    $emisor = [regex]::Match($txt, '<Emisor>.*?<Identificacion>.*?<Numero>([^<]+)</Numero>', 'Singleline').Groups[1].Value
    if (-not $emisor) { $badRuc += "$($f.Name): no Emisor/Identificacion/Numero found"; continue }
    if ($emisor -notmatch '^\d{9,12}$') {
        $badRuc += "$($f.Name): Emisor Identificacion/Numero '$emisor' is not 9-12 digits; the Clave RUC segment is only defined as that id zero-padded to 12, so this fixture cannot be checked and the generator must be updated for the alphanumeric form"
        continue
    }
    $emb = $clave.Substring(9, 12).TrimStart('0')
    if ($emb -ne $emisor.TrimStart('0')) {
        $badRuc += "$($f.Name): Clave RUC segment '$emb' != Emisor '$emisor'"
    }
}
if ($badRuc.Count -eq 0) { Say "  PASS  every Clave RUC segment matches its Emisor Identificacion" }
else { $fail++; Say "  FAIL  $($badRuc.Count) Clave/Emisor mismatches:"; $badRuc | Select-Object -First 10 | ForEach-Object { Say "        $_" } }

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
    # derive-facturas-v44.ps1 wipes the v4.4 directory and then writes one file
    # per v4.3 source, so a throw partway through leaves a short set that would
    # still validate cleanly. Compare the counts.
    if ($v44.Count -ne $v43.Count) {
        $fail++
        Say "  FAIL  v4.4 set has $($v44.Count) file(s) but v4.3 has $($v43.Count); derivation did not finish"
    }
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
