# Generate per-document JAXB wrapper subclasses for all Hacienda v4.4 document types.
# Each wrapper extends the shared entity, inherits all @XmlElement fields,
# and provides a copy constructor that wraps nested entity types.

$BASE = "F:\Documents\GitHub\Mercurius\mercurius-quarkus\src\main\java\Models\Jaxb"

# Document-specific namespaces (from @XmlRootElement in Documento classes)
$DOCUMENTS = @{
    FE  = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronica"
    TE  = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/tiqueteElectronico"
    NC  = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/notaCreditoElectronica"
    ND  = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/notaDebitoElectronica"
    FEE = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronicaExportacion"
    FCE = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/facturaElectronicaCompra"
    MR  = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/mensajeReceptor"
    REP = "https://cdn.comprobanteselectronicos.go.cr/xml-schemas/v4.4/reciboElectronicoPago"
}

# All shared entity types that need wrappers
# Format: "SimpleName" = @{
#   sourcePackage = "Models.X.Y"
#   nestedFields = @{ "fieldName" = "EntityType" }  # fields that need wrapper conversion
#   collectionFields = @{ "fieldName" = "EntityType" }  # List<T> fields that need wrapping
#   docTypeExclusions = @("FE", ...)  # document types that DON'T use this entity (optional)
# }
$ENTITY_TYPES = @{
    "Encabezado" = @{
        sourcePackage = "Models.Encabezado"
        nestedFields = @{ "emisor" = "Emisor"; "receptor" = "Receptor" }
        collectionFields = @{}
        docTypeExclusions = @("MR")
    }
    "Emisor" = @{
        sourcePackage = "Models.Encabezado"
        nestedFields = @{ "identificacion" = "IdentificacionEmisor"; "ubicacion" = "Ubicacion"; "telefono" = "Telefono" }
        collectionFields = @{ "correosElectronicos" = "CorreoElectronicoEmisor" }
    }
    "IdentificacionEmisor" = @{
        sourcePackage = "Models.Encabezado"
        nestedFields = @{}
        collectionFields = @{}
    }
    "Receptor" = @{
        sourcePackage = "Models.Encabezado"
        nestedFields = @{ "identificacion" = "IdentificacionReceptor"; "ubicacion" = "Ubicacion"; "telefono" = "Telefono" }
        collectionFields = @{}
    }
    "IdentificacionReceptor" = @{
        sourcePackage = "Models.Encabezado"
        nestedFields = @{}
        collectionFields = @{}
    }
    "Ubicacion" = @{
        sourcePackage = "Models.Encabezado"
        nestedFields = @{}
        collectionFields = @{}
    }
    "Telefono" = @{
        sourcePackage = "Models.Encabezado"
        nestedFields = @{}
        collectionFields = @{}
    }
    "CorreoElectronicoEmisor" = @{
        sourcePackage = "Models.Encabezado"
        nestedFields = @{}
        collectionFields = @{}
    }
    "DetalleServicio" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{ "lineasDetalle" = "LineaDetalle" }
    }
    "LineaDetalle" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{ "detalleSurtido" = "DetalleSurtido" }
        collectionFields = @{ "impuestos" = "Impuesto"; "codigosComerciales" = "CodigoComercial"; "descuentos" = "Descuento"; "numerosVINoSerie" = "NumeroVINoSerie" }
    }
    "Impuesto" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{ "exoneracion" = "Exoneracion"; "datosImpuestoEspeficio" = "DatosImpuestoEspecifico" }
        collectionFields = @{}
    }
    "CodigoComercial" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{}
    }
    "Descuento" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{}
    }
    "Exoneracion" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{}
    }
    "DatosImpuestoEspecifico" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{}
    }
    "NumeroVINoSerie" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{}
    }
    "OtroCargo" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{ "identificacionTercero" = "IdentificacionTercero" }
        collectionFields = @{}
    }
    "IdentificacionTercero" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{}
    }
    "DetalleSurtido" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{ "lineasDetalleSurtido" = "LineaDetalleSurtido" }
    }
    "LineaDetalleSurtido" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{ "codigosComercialesSurtidos" = "CodigoComercialSurtido"; "descuentosSurtidos" = "DescuentoSurtido"; "impuestosSurtidos" = "ImpuestoSurtido" }
    }
    "CodigoComercialSurtido" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{}
    }
    "DescuentoSurtido" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{}
    }
    "ImpuestoSurtido" = @{
        sourcePackage = "Models.Detalles"
        nestedFields = @{}
        collectionFields = @{}
    }
    "ResumenFactura" = @{
        sourcePackage = "Models.Resumen"
        nestedFields = @{ "codigoMoneda" = "CodigoTipoMoneda" }
        collectionFields = @{ "totalDesgloseImpuestos" = "TotalDesgloseImpuesto"; "mediosPago" = "MedioPagoR" }
    }
    "CodigoTipoMoneda" = @{
        sourcePackage = "Models.Resumen"
        nestedFields = @{}
        collectionFields = @{}
    }
    "TotalDesgloseImpuesto" = @{
        sourcePackage = "Models.Resumen"
        nestedFields = @{}
        collectionFields = @{}
    }
    "MedioPagoR" = @{
        sourcePackage = "Models.Resumen"
        nestedFields = @{}
        collectionFields = @{}
    }
    "InformacionReferencia" = @{
        sourcePackage = "Models.Referencias"
        nestedFields = @{}
        collectionFields = @{}
    }
}

# Helper: generate wrapper copy-constructor body lines for nested entity fields
function Get-CopyLines($entity, $doc) {
    $lines = @()
    $info = $ENTITY_TYPES[$entity]
    
    # Simple fields via JaxbCopier
    $lines += "        JaxbCopier.copySimpleFields(src, this);"
    $lines += ""
    
    # Nested single entity fields
    foreach ($kv in $info.nestedFields.GetEnumerator()) {
        $fieldName = $kv.Key
        $fieldType = $kv.Value
        $getter = "get$($fieldName.Substring(0,1).ToUpper())$($fieldName.Substring(1))"
        $lines += "        if (src.$getter() != null) {"
        $lines += "            this.$fieldName = new $fieldType(src.$getter());"
        $lines += "        }"
    }
    
    # Collection fields
    foreach ($kv in $info.collectionFields.GetEnumerator()) {
        $fieldName = $kv.Key
        $fieldType = $kv.Value
        $getter = "get$($fieldName.Substring(0,1).ToUpper())$($fieldName.Substring(1))"
        $lines += "        if (src.$getter() != null) {"
        $lines += "            this.$fieldName = src.$getter().stream()"
        $lines += "                .map(e -> new $fieldType(e))"
        $lines += "                .collect(java.util.stream.Collectors.toList());"
        $lines += "        }"
    }
    
    return $lines
}

# Main generation loop
foreach ($doc in $DOCUMENTS.Keys) {
    $ns = $DOCUMENTS[$doc]
    $dir = "$BASE\$doc"
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    
    # 1) package-info.java
    $pkgInfo = "package Models.Jaxb.$doc;`n"
    $pkgInfo += "`n"
    $pkgInfo += "import jakarta.xml.bind.annotation.XmlNsForm;`n"
    $pkgInfo += "import jakarta.xml.bind.annotation.XmlSchema;`n"
    $pkgInfo += "`n"
    $pkgInfo += "@XmlSchema(namespace = ""$ns"",`n"
    $pkgInfo += "           elementFormDefault = XmlNsForm.QUALIFIED,`n"
    $pkgInfo += "           xmlns = { @XmlNs(prefix = """",`n"
    $pkgInfo += "                              namespaceURI = ""$ns"") })`n"
    $pkgInfo += "package Models.Jaxb.$doc;`n"
    
    Set-Content -Path "$dir\package-info.java" -Value $pkgInfo -NoNewline
    
    # 2) Wrapper classes for each entity type
    foreach ($entityName in $ENTITY_TYPES.Keys) {
        $info = $ENTITY_TYPES[$entityName]
        $srcPkg = $info.sourcePackage
        
        # Skip if this doc type is excluded
        if ($info.docTypeExclusions -and $info.docTypeExclusions -contains $doc) {
            continue
        }
        
        $copyLines = Get-CopyLines $entityName $doc
        
        $wrapperCode = "package Models.Jaxb.$doc;`n"
        $wrapperCode += "`n"
        $wrapperCode += "$($srcPkg).$entityName"  # import
        $wrapperCode = "package Models.Jaxb.$doc;`n"
        $wrapperCode += "`n"
        $wrapperCode += "import $srcPkg.$entityName;"  # wait, we don't need import if extending with full path
        
        # Actually, let me just write the full path in extends
        $wrapperCode = "package Models.Jaxb.$doc;`n"
        $wrapperCode += "`n"
        $wrapperCode += "import $srcPkg.$entityName;"  # Actually this would clash!
        $wrapperCode += "import jakarta.xml.bind.annotation.XmlAccessType;`n"
        $wrapperCode += "import jakarta.xml.bind.annotation.XmlAccessorType;`n"
        $wrapperCode += "`n"
        
        # The class needs to use FQN for extends since local package has same simple name
        # Wait, no - the wrapper IS in a subpackage. Models.Jaxb.FE.Encabezado extends Models.Encabezado.Encabezado.
        # But we imported Models.Encabezado.Encabezado which is the SHARED entity, while Models.Jaxb.FE.Encabezado is the wrapper.
        # This is a naming collision if we import the shared entity.
        
        # Solution: use the FQN in extends, don't import
        $wrapperCode = "package Models.Jaxb.$doc;`n"
        $wrapperCode += "`n"
        $wrapperCode += "import jakarta.xml.bind.annotation.XmlAccessType;`n"
        $wrapperCode += "import jakarta.xml.bind.annotation.XmlAccessorType;`n"
        $wrapperCode += "import $srcPkg.$entityName;`n"
        $wrapperCode += "`n"
        
        # Wait, if Models.Jaxb.FE has FE.Encabezado and we import Models.Encabezado.Encabezado,
        # there would be a naming collision between FE.Encabezado (this file) and the imported Encabezado.
        # Java would complain.
        
        # Correct approach: DON'T import the shared entity. Use FQN in the extends clause.
        $wrapperCode = "package Models.Jaxb.$doc;`n"
        $wrapperCode += "`n"
        $wrapperCode += "import jakarta.xml.bind.annotation.XmlAccessType;`n"
        $wrapperCode += "import jakarta.xml.bind.annotation.XmlAccessorType;`n"
        $wrapperCode += "`n"
        
        if ($entityName -eq "Encabezado") {
            # Don't import - use FQN
        }
        
        # Hmm, actually the simple thing: import the SHARED entity but don't import SELF.
        # Since the wrapper class doesn't import itself, there's no collision.
        # The wrapper extends $srcPkg.$entityName. If we import $srcPkg.$entityName, 
        # the simple name $entityName refers to the imported one (the shared entity).
        # The wrapper class itself is referred to by ITS simple name (since it's in this file).
        # So there IS a collision: the simple name inside the file would be ambiguous.
        
        # Fix: use FQN in the extends clause, don't import.
        $wrapperCode = "package Models.Jaxb.$doc;`n"
        $wrapperCode += "`n"
        $wrapperCode += "import jakarta.xml.bind.annotation.XmlAccessType;`n"
        $wrapperCode += "import jakarta.xml.bind.annotation.XmlAccessorType;`n"
        $wrapperCode += "`n"
        $wrapperCode += "@XmlAccessorType(XmlAccessType.FIELD)`n"
        $wrapperCode += "public class $entityName extends $srcPkg.$entityName {`n"
        $wrapperCode += "`n"
        $wrapperCode += "    public $entityName() {`n"
        $wrapperCode += "        super();`n"
        $wrapperCode += "    }`n"
        $wrapperCode += "`n"
        $wrapperCode += "    public $entityName($srcPkg.$entityName src) {`n"
        
        foreach ($line in $copyLines) {
            $wrapperCode += "        $line`n"
        }
        
        $wrapperCode += "    }`n"
        $wrapperCode += "}"
        
        Set-Content -Path "$dir\$entityName.java" -Value $wrapperCode -NoNewline
    }
}

Write-Host "Generated wrapper files for $($DOCUMENTS.Count) document types"
