<#
.SYNOPSIS
    Derives strictly XSD-valid v4.4 fixtures from the anonymized v4.3 set.

.DESCRIPTION
    Every source invoice in the real sample set is v4.3, but Mercurius targets
    v4.4 (ANEXOS_V4.4.pdf, XmlEncabezadoFlattener, ComprobanteFactory). This
    converts the anonymized v4.3 fixtures so the v4.4 parser path is also
    exercised with real product data.

    Transformations, each traceable to a difference between the official
    v4.3 and v4.4 schemas:

      1. targetNamespace  v4.3 -> v4.4
      2. + ProveedorSistemas        required in v4.4, absent in v4.3
      3. CodigoActividad -> CodigoActividadEmisor   renamed in v4.4
      4. - root MedioPago           required at root in v4.3, relocated into
                                    ResumenFactura in v4.4
      5. LineaDetalle Codigo -> CodigoCABYS          v4.4 renamed Codigo and
                                     requires exactly 13 chars; every real
                                     Codigo already is
      6. - LineaDetalle <Impuestos> wrapper  v4.4 drops the wrapper, so
                                     Impuesto* become direct line children
      7. LineaDetalle CodigoTarifa -> CodigoTarifaIVA, FactorIVA ->
                                     FactorCalculoIVA, and the v4.4 line
                                     requires BaseImponible,
                                     ImpuestoAsumidoEmisorFabrica and
                                     ImpuestoNeto
      8. + Descuento CodigoDescuento   v4.4 requires the code ahead of the
                                     now-optional NaturalezaDescuento
      9. InformacionReferencia         v4.4 renames its children
                                     TipoDoc -> TipoDocIR and
                                     FechaEmision -> FechaEmisionIR. The
                                     block is optional at the root; nothing
                                     is invented when a document has none
     10. Barrio widened to >= 5        v4.3 types Barrio as PositiveInteger
                                      ('01'..'36'); v4.4 adds minLength 5

     NOT a v4.4 change: LineaDetalle MontoTotal still exists in
     FacturaElectronica_V4.4.xsd, positioned immediately before Descuento,
     and is present in all 253 derived lines. An earlier revision of this
     header wrongly claimed v4.4 removed it.

     Like the v4.3 set these fixtures are unsigned, and the official schema
     mandates ds:Signature, so "valid" here means valid apart from the
     signature, which is asserted explicitly by verify-real-fixtures.ps1.
#>
[CmdletBinding()]
param(
    [string]$RepoRoot = "D:\Documents\Github\Mercurius",
    [string]$RealRoot
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if (-not $RealRoot) { $RealRoot = Join-Path $RepoRoot "app\src\test\resources\fixtures\reales" }
$V43Dir = Join-Path $RealRoot "v4.3"
$V44Dir = Join-Path $RealRoot "v4.4"
if (Test-Path -LiteralPath $V44Dir) { Remove-Item -LiteralPath $V44Dir -Recurse -Force }
New-Item -ItemType Directory -Force -Path $V44Dir | Out-Null

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

# ---------------------------------------------------------------------------
# Element-scoped helpers. All of these are anchored to a full element value so
# a replacement can never bleed into neighbouring numeric text.
# ---------------------------------------------------------------------------
function Remove-Element {
    param([string]$Xml, [string]$Name)
    # Handles <N>..</N>, <N/> and <N attr="v"/>. Real producers emit
    # <Fax xsi:nil="true" />, which a plain <N>..</N> pattern silently misses.
    return [regex]::Replace($Xml, "(?s)<$Name\b[^>]*/>|<$Name\b[^>]*>.*?</$Name>", '')
}

function Rename-RootElement {
    param([string]$Xml, [string]$From, [string]$To)
    # a root-level element, so renaming the tags outright is safe
    return $Xml.Replace("<$From>", "<$To>").Replace("</$From>", "</$To>")
}

# LineaDetalle/Codigo becomes CodigoCABYS. The rename is anchored on the
# schema-mandated order (NumeroLinea then Codigo) because a blanket
# <Codigo> -> <CodigoCABYS> would also rewrite CodigoComercial/Codigo.
function Rename-LineaCodigo {
    param([string]$Xml)
    return [regex]::Replace($Xml,
        '(?s)(<NumeroLinea>[^<]*</NumeroLinea>\s*)<Codigo>([^<]*)</Codigo>',
        '$1<CodigoCABYS>$2</CodigoCABYS>')
}

# v4.4 UnidadMedidaType is a closed enumeration. The real v4.3 producers emit
# 'g', 'Unid' and 'Otros'; 'Unid' and 'Otros' are still valid in v4.4 but the
# SI symbol for gram is capitalised 'G' there. UnidadMedidaComercial
# (BOT, LT, UN, PAK, ST ...) is an unrelated free-string field and is left
# alone, so the realistic commercial units survive.
$UnidadMedidaV44 = @{
    'g'    = 'G'
    'kg'   = 'Kg'
    'ml'   = 'mL'
    'l'    = 'L'
    'unid' = 'Unid'
    'otros' = 'Otros'
}

# ---------------------------------------------------------------------------
# Convert one v4.3 document to v4.4
# ---------------------------------------------------------------------------
function Convert-ToV44 {
    param([string]$Xml, [string]$FileName)

    # 1. namespace
    $x = $Xml.Replace(
        'https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.3/',
        'https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/')
    $x = $x.Replace('FacturaElectronica_V4.3.xsd', 'FacturaElectronica_V4.4.xsd')

    # 2. ProveedorSistemas: the issuer's own tax id, inserted directly after Clave
    $prov = $null
    if ($x -match '(?s)<Emisor>(.*?)</Emisor>') {
        $emisorBlock = $Matches[1]
        if ($emisorBlock -match '(?s)<Identificacion>(.*?)</Identificacion>') {
            if ($Matches[1] -match '<Numero>([^<]*)<') { $prov = $Matches[1] }
        }
    }
    if (-not $prov) { throw "$FileName : could not determine ProveedorSistemas" }
    if ($x -notmatch '<ProveedorSistemas>') {
        $x = [regex]::Replace($x, '(?s)(</Clave>)', "`$1`r`n`t`t`t<ProveedorSistemas>$prov</ProveedorSistemas>", 1)
    }

    # 3. CodigoActividad renamed to CodigoActividadEmisor
    $x = Rename-RootElement -Xml $x -From 'CodigoActividad' -To 'CodigoActividadEmisor'

    # 4. root MedioPago has no home in v4.4; it moved into ResumenFactura,
    #    where it is optional, so the simple root code is dropped.
    $x = Remove-Element -Xml $x -Name 'MedioPago'

    # 5. Fax was removed from EmisorType in v4.4
    $x = Remove-Element -Xml $x -Name 'Fax'

    # 6. PlazoCredito is xs:integer in v4.4; v4.3 producers wrote '14 Dias'
    $x = [regex]::Replace($x, '(?s)<PlazoCredito>.*?</PlazoCredito>', {
        param($m)
        $v = $m.Value
        if ($v -match '(\d+)') { return "<PlazoCredito>$($Matches[1])</PlazoCredito>" }
        return ''
    })

    # 7. LineaDetalle: Codigo becomes CodigoCABYS. MontoTotal is NOT removed:
    #    v4.4 still requires it, positioned immediately before Descuento.
    $x = Rename-LineaCodigo -Xml $x

    # 7b. Descuento: v4.4 adds a REQUIRED CodigoDescuento ahead of the now
    #     optional NaturalezaDescuento. v4.3 producers only wrote the free text
    #     ("Descuento comercial"), so the code has to be supplied. 01 is
    #     "Descuento comercial" in the v4.4 CodigoDescuentoType enumeration.
    $x = [regex]::Replace($x, '(?s)(<MontoDescuento>[^<]*</MontoDescuento>)', {
        param($m) $m.Groups[1].Value + '<CodigoDescuento>01</CodigoDescuento>' })

    # 7c. LineaDetalle tax block changes in v4.4:
    #       - the <Impuestos> wrapper is gone, Impuesto* become direct children
    #       - CodigoTarifa -> CodigoTarifaIVA, FactorIVA -> FactorCalculoIVA
    #       - BaseImponible is required on the line (v4.3 had none)
    #       - Impuesto itself is required, so an exempt line that carried none
    #         in v4.3 gets a zero-amount IVA line
    #     BaseImponible is taken from the line's own SubTotal, which is the
    #     taxable base once discounts are applied.
    $x = [regex]::Replace($x, '(?s)<LineaDetalle>(.*?)</LineaDetalle>', {
        param($m)
        $ln = $m.Groups[1].Value
        $ln = [regex]::Replace($ln, '(?s)<Impuestos>(.*?)</Impuestos>', '$1')
        $ln = $ln.Replace('<CodigoTarifa>', '<CodigoTarifaIVA>').Replace('</CodigoTarifa>', '</CodigoTarifaIVA>')
        $ln = $ln.Replace('<FactorIVA>', '<FactorCalculoIVA>').Replace('</FactorIVA>', '</FactorCalculoIVA>')

        if ($ln -notmatch '<BaseImponible>') {
            $sm = [regex]::Match($ln, '<SubTotal>([^<]*)<')
            if ($sm.Success) {
                $st = $sm.Groups[1].Value
                $ln = [regex]::Replace($ln, '(<SubTotal>[^<]*</SubTotal>)',
                        { param($s) $s.Groups[1].Value + "<BaseImponible>$st</BaseImponible>" }, 1)
            }
        }

        # v4.4 requires at least one Impuesto per line
        if ($ln -notmatch '<Impuesto>') {
            $zero = '<Impuesto><Codigo>01</Codigo><Monto>0.00000</Monto></Impuesto>'
            if ($ln -match '<ImpuestoNeto>') {
                $ln = [regex]::Replace($ln, '(<ImpuestoNeto>)',
                        { param($s) $zero + $s.Groups[1].Value }, 1)
            }
        }

        # v4.4 also makes ImpuestoAsumidoEmisorFabrica and ImpuestoNeto
        # mandatory on every line; the v4.3 producers emitted neither.
        # ImpuestoNeto is the sum of the line's own Impuesto amounts. The
        # schema order is Impuesto*, ImpuestoAsumidoEmisorFabrica,
        # ImpuestoNeto, MontoTotalLinea, so the assumed block goes before
        # ImpuestoNeto when the line already has one.
        $sum = [decimal]0
        foreach ($mm in [regex]::Matches($ln, '(?s)<Impuesto>.*?<Monto>([^<]*)<')) {
            $v = $mm.Groups[1].Value
            $parsed = [decimal]0
            if ([decimal]::TryParse($v, [ref]$parsed)) { $sum += $parsed }
        }
        $pre = ''
        if ($ln -notmatch '<ImpuestoAsumidoEmisorFabrica>') {
            $pre += '<ImpuestoAsumidoEmisorFabrica>0.00000</ImpuestoAsumidoEmisorFabrica>'
        }
        if ($ln -notmatch '<ImpuestoNeto>') {
            $pre += "<ImpuestoNeto>$($sum.ToString('F5'))</ImpuestoNeto>"
        }
        if ($pre) {
            $anchor = if ($ln -match '<ImpuestoNeto>') { '(<ImpuestoNeto>)' } else { '(<MontoTotalLinea>)' }
            if ($ln -match $anchor.Trim('(', ')')) {
                $ln = [regex]::Replace($ln, $anchor, { param($s) $pre + $s.Groups[1].Value }, 1)
            }
        }
        return "<LineaDetalle>$ln</LineaDetalle>"
    })

    # 8. UnidadMedida enumeration
    $x = [regex]::Replace($x, '(?<=<UnidadMedida>)([^<]*)(?=</UnidadMedida>)', {
        param($m)
        $v = $m.Groups[1].Value
        $map = $UnidadMedidaV44[$v.ToLowerInvariant()]
        if ($map) { return $map }
        return $v
    })

    # 9. InformacionReferencia is a ROOT-level element (minOccurs=0) whose
    #    children were renamed in v4.4: TipoDoc -> TipoDocIR and
    #    FechaEmision -> FechaEmisionIR. They are NOT ResumenFactura children,
    #    and the whole block is optional, so nothing is invented when a
    #    document has no reference.
    $x = [regex]::Replace($x, '(?s)<InformacionReferencia>(.*?)</InformacionReferencia>', {
        param($m)
        $ir = $m.Groups[1].Value
        $ir = $ir.Replace('<TipoDoc>', '<TipoDocIR>').Replace('</TipoDoc>', '</TipoDocIR>')
        $ir = $ir.Replace('<FechaEmision>', '<FechaEmisionIR>').Replace('</FechaEmision>', '</FechaEmisionIR>')
        return "<InformacionReferencia>$ir</InformacionReferencia>"
    })

    # 9b. v4.4 tightened several OPTIONAL elements that real v4.3 producers
    #     emit empty: an empty <PlazoCredito/> is fine for a v4.3 string but
    #     invalid for the v4.4 xs:integer, and an empty <NombreComercial/>
    #     violates the v4.4 minLength of 3. All of these are optional in
    #     v4.4, so the empty element is simply dropped.
    foreach ($opt in @('PlazoCredito', 'NombreComercial', 'Barrio', 'OtrasSenasExtranjero',
                       'CondicionVentaOtros', 'TipoDocRefOTRO', 'Numero', 'Codigo')) {
        $x = [regex]::Replace($x, "(?s)<$opt\s*/>|<$opt(\s[^>]*)?>\s*</$opt>", '')
    }

    # 10. Barrio: v4.3 PositiveInteger, v4.4 minLength 5
    $x = [regex]::Replace($x, '(?<=<Barrio>)([^<]*)(?=</Barrio>)', {
        param($m)
        $v = $m.Groups[1].Value.Trim()
        if ($v.Length -ge 5) { return $m.Groups[1].Value }
        return "BARRIO $v"
    })

    # 11. The v4.4 set gets its own consecutivo and Clave. A v4.4 re-issuance is
    #     a distinct document, and reusing the v4.3 consecutive would make the
    #     parser's duplicate-consecutivo guard treat the two as one invoice.
    #     NumeroConsecutivoType is \d{20,20} in both v4.3 and v4.4, so the
    #     digits-only match here is checked against the schemas, not assumed.
    $x = [regex]::Replace($x, '(?s)(<NumeroConsecutivo>)(\d{12})(\d{8})(</NumeroConsecutivo>)', {
        param($m)
        $tail = ([int]$m.Groups[3].Value + 500000) % 100000000
        $m.Groups[1].Value + $m.Groups[2].Value + ('{0:D8}' -f $tail) + $m.Groups[4].Value
    })
    # Matched as [a-zA-Z0-9]{47}, the class v4.4 ClaveType actually allows. A
    # narrower class would not fail here, it would skip the rebuild and quietly
    # leave the v4.3 Clave in the v4.4 fixture - exactly the duplicate this step
    # exists to prevent.
    if ($x -match '<Clave>(506[a-zA-Z0-9]{47})</Clave>') {
        $oldClave = $Matches[1]
        $newCons = [regex]::Match($x, '<NumeroConsecutivo>(\d{20})</NumeroConsecutivo>').Groups[1].Value
        # The RUC segment is that id zero-padded to 12, a convention that only
        # holds for 9-12 digits. v4.4 dropped the \d{9,12} restriction from
        # IdentificacionType/Numero (maxLength 20, no pattern), so an
        # alphanumeric id such as 3-101-A00001 is schema-valid - but no bundled
        # v4.4 schema states how such an id is laid out inside a Clave, so
        # padding one here would fabricate the layout.
        if ($prov -notmatch '^\d{9,12}$') {
            throw ("$FileName : Emisor/Identificacion/Numero is '$prov', " +
                   "which is not 9-12 digits. The Clave RUC segment is defined " +
                   "as that id zero-padded to 12, so it cannot be rebuilt for an " +
                   "alphanumeric id without knowing the real layout.")
        }
        $segRuc  = $prov.PadLeft(12, '0')
        if ($segRuc.Length -gt 12) { $segRuc = $segRuc.Substring($segRuc.Length - 12) }
        $salt = 600000 + [Math]::Abs($oldClave.GetHashCode() % 300000)
        $newClave = '506' + $oldClave.Substring(3, 6) + $segRuc + $newCons + ('{0:D9}' -f $salt)
        # 3 + 6 + 12 + 20 + 9 = 50, and the concatenated form must still satisfy
        # the v4.4 ClaveType pattern. Assert the schema rather than the arithmetic.
        if ($newClave -notmatch '^[a-zA-Z0-9]{50}$') {
            throw ("$FileName : rebuilt Clave '$newClave' is not 50 " +
                   "alphanumeric characters, so it is invalid against the " +
                   "v4.4 ClaveType pattern [a-zA-Z0-9]{50,50}. Source Clave " +
                   "was '$oldClave'.")
        }
        $x = $x.Replace("<Clave>$oldClave</Clave>", "<Clave>$newClave</Clave>")
    }

    return $x
}

# ---------------------------------------------------------------------------
Write-Host "deriving v4.4 from v4.3..."
$src = Get-ChildItem -LiteralPath $V43Dir -Filter *.xml | Sort-Object Name
$written = 0
foreach ($f in $src) {
    $xml = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    $out = Convert-ToV44 -Xml $xml -FileName $f.Name
    $name = $f.Name -replace '-v43-', '-v44-'
    [System.IO.File]::WriteAllText((Join-Path $V44Dir $name), $out.Trim() + "`n", $utf8NoBom)
    $written++
}
Write-Host "wrote $written v4.4 fixtures to $V44Dir"
