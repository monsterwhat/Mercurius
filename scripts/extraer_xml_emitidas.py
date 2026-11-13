"""Parsea los XML de facturas emitidas (v4.4) y genera CSV para importarlas.

Salida en build/dbf_reconciliacion/:
  xml_enc.csv   cabeceras (clave, tipo, consecutivo, fecha, emisor, receptor, ...)
  xml_res.csv   resumen por clave
  xml_lin.csv   líneas por clave
El SQL de importación filtra por clave: lo que ya está en `encabezado` se ignora.
"""
import csv
import os
import re
import xml.etree.ElementTree as ET
from pathlib import Path

RAIZ = Path(os.environ.get(
    "MERCURIUS_XML_DIR",
    str(Path.home() / "AppData" / "Local" / "Temp" / "opencode" / "facturas_xml" / "extraido")))
OUT = Path(os.environ.get(
    "MERCURIUS_CSV_DIR",
    r"F:\Documents\GitHub\Mercurius-clean\build\dbf_reconciliacion"))
OUT.mkdir(parents=True, exist_ok=True)

TIPOS = {
    "TiqueteElectronico": "04",
    "FacturaElectronica": "01",
    "NotaCreditoElectronica": "02",
    "NotaDebitoElectronica": "03",
    "FacturaElectronicaExportacion": "05",
    "FacturaElectronicaCompra": "08",
    "ReciboElectronicoPago": "10",
}


def sin_ns(tag):
    return tag.split("}", 1)[-1]


def texto(nodo, ruta, default=""):
    if nodo is None:
        return default
    actual = nodo
    for parte in ruta.split("/"):
        if actual is None:
            return default
        actual = next((h for h in actual if sin_ns(h.tag) == parte), None)
    if actual is None or actual.text is None:
        return default
    return actual.text.strip()


def numero(valor, default="0"):
    limpio = (valor or "").strip().replace(",", "")
    if not limpio:
        return default
    try:
        float(limpio)
        return limpio
    except ValueError:
        return default


vistos = set()
enc, res, lin = [], [], []
xmls = 0
for ruta in RAIZ.rglob("*.xml"):
    xmls += 1
    try:
        root = ET.parse(ruta).getroot()
    except ET.ParseError:
        continue
    tipo_xml = sin_ns(root.tag)
    if tipo_xml not in TIPOS:
        continue
    clave = texto(root, "Clave")
    if not clave or clave in vistos:
        continue
    vistos.add(clave)

    fecha = texto(root, "FechaEmision").replace("T", " ")[:19]
    emisor_num = texto(root, "Emisor/Identificacion/Numero")
    emisor_nom = texto(root, "Emisor/Nombre")
    receptor_num = texto(root, "Receptor/Identificacion/Numero") or texto(root, "Receptor/IdentificacionExtranjero")
    receptor_nom = texto(root, "Receptor/Nombre")
    receptor_tipo = texto(root, "Receptor/Identificacion/Tipo")

    enc.append([
        clave, TIPOS[tipo_xml], texto(root, "NumeroConsecutivo"), fecha,
        texto(root, "CondicionVenta", "01"), texto(root, "PlazoCredito"),
        texto(root, "CodigoActividadEmisor"), emisor_num, emisor_nom,
        receptor_num, receptor_nom, receptor_tipo,
    ])

    res.append([
        clave, texto(root, "ResumenFactura/CodigoTipoMoneda/CodigoMoneda", "CRC"),
        numero(texto(root, "ResumenFactura/CodigoTipoMoneda/TipoCambio"), "1"),
        numero(texto(root, "ResumenFactura/TotalServGravados")),
        numero(texto(root, "ResumenFactura/TotalServExentos")),
        numero(texto(root, "ResumenFactura/TotalMercanciasGravadas")),
        numero(texto(root, "ResumenFactura/TotalMercanciasExentas")),
        numero(texto(root, "ResumenFactura/TotalGravado")),
        numero(texto(root, "ResumenFactura/TotalExento")),
        numero(texto(root, "ResumenFactura/TotalNoSujeto")),
        numero(texto(root, "ResumenFactura/TotalVenta")),
        numero(texto(root, "ResumenFactura/TotalDescuentos")),
        numero(texto(root, "ResumenFactura/TotalVentaNeta")),
        numero(texto(root, "ResumenFactura/TotalImpuesto")),
        numero(texto(root, "ResumenFactura/TotalComprobante")),
    ])

    for linea in root.iter():
        if sin_ns(linea.tag) != "LineaDetalle":
            continue
        lin.append([
            clave,
            texto(linea, "NumeroLinea", "1"),
            texto(linea, "Detalle")[:200],
            numero(texto(linea, "Cantidad")),
            texto(linea, "UnidadMedida", "Unid"),
            numero(texto(linea, "PrecioUnitario")),
            numero(texto(linea, "MontoTotal")),
            numero(texto(linea, "SubTotal")),
            numero(texto(linea, "BaseImponible")),
            numero(texto(linea, "ImpuestoNeto")),
            numero(texto(linea, "MontoTotalLinea")),
            texto(linea, "CodigoCABYS"),
        ])


def escribir(nombre, cabecera, filas):
    destino = OUT / nombre
    with destino.open("w", newline="", encoding="utf-8") as fh:
        w = csv.writer(fh)
        w.writerow(cabecera)
        w.writerows(filas)
    print(f"{nombre}: {len(filas)} -> {destino}")


print(f"xml revisados: {xmls}, claves únicas: {len(vistos)}")
escribir("xml_enc.csv",
         ["clave", "codigo_documento", "consecutivo", "fecha", "condicion_venta",
          "plazo_credito", "actividad_emisor", "emisor_numero", "emisor_nombre",
          "receptor_numero", "receptor_nombre", "receptor_tipo"], enc)
escribir("xml_res.csv",
         ["clave", "moneda", "tipo_cambio", "serv_gravados", "serv_exentos",
          "merc_gravadas", "merc_exentas", "total_gravado", "total_exento",
          "total_nosujeto", "total_venta", "total_descuentos", "total_venta_neta",
          "total_impuesto", "total_comprobante"], res)
escribir("xml_lin.csv",
         ["clave", "numero", "detalle", "cantidad", "unidad", "precio_unitario",
          "monto_total", "sub_total", "base_imponible", "impuesto_neto",
          "monto_total_linea", "codigocabys"], lin)
