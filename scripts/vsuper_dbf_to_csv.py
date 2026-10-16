#!/usr/bin/env python3
"""Convert vSuper FoxPro DBF files into reviewable CSV exports.

This is intentionally a *read-only* conversion step. It does NOT touch PostgreSQL and
does NOT decide Mercurius column names final mappings. It keeps the original field
names so the resulting files can be reviewed, cleaned, or imported separately.
"""
import argparse
import csv
import os
import struct
from pathlib import Path


DBF_EXTENSIONS = (".dbf", ".DBF", ".Dbf")


def read_dbf(path: Path):
    with path.open("rb") as f:
        header = f.read(32)
        if len(header) != 32:
            raise ValueError(f"invalid DBF header: {path}")

        num_records, header_size, record_size = struct.unpack("<IHH", header[4:12])
        fields = []
        f.seek(32)
        while True:
            raw = f.read(32)
            if not raw or raw[0:1] == b"\x0d":
                break
            if raw[0] == 0x00:
                break
            name = raw[0:11].split(b"\x00")[0].decode("latin-1").strip()
            ftype = raw[11:12].decode("latin-1")
            length = raw[16]
            if name:
                fields.append((name, ftype, length))

        rows = []
        for i in range(num_records):
            offset = header_size + i * record_size
            f.seek(offset)
            record = f.read(record_size)
            if len(record) != record_size:
                break
            status = record[0:1]
            if status == b"\x2a":
                continue  # deleted row
            row = {}
            pos = 1
            for name, ftype, length in fields:
                chunk = record[pos:pos + length]
                pos += length
                row[name] = decode_value(ftype, chunk)
            rows.append(row)
    return fields, rows


def decode_value(ftype, chunk):
    ftype = ftype.upper()
    raw = chunk.decode("latin-1", errors="replace")
    value = raw.rstrip("\x00 ").rstrip("\x00")
    if ftype == "D":
        value = value.strip()
        if len(value) == 8 and value.isdigit():
            return f"{value[0:4]}-{value[4:6]}-{value[6:8]}"
        return value
    if ftype in ("T", "8"):
        value = value.strip()
        # FoxPro T is a binary timestamp; keep readable value when possible.
        return value
    if ftype == "L":
        return value.strip().upper() in ("T", "Y", "TRUE", "1")
    return value.strip()


def write_csv(out_path, fields, rows):
    headers = [name for name, _, _ in fields]
    with out_path.open("w", newline="", encoding="utf-8-sig") as f:
        writer = csv.DictWriter(f, fieldnames=headers)
        writer.writeheader()
        for row in rows:
            writer.writerow(row)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True, help="directory containing extracted VSUPER DBFs")
    parser.add_argument("--output", required=True, help="directory for generated CSV files")
    parser.add_argument("--tables", nargs="*", help="optional DBF stems to convert; defaults to a curated set")
    args = parser.parse_args()

    src = Path(args.input)
    dst = Path(args.output)
    dst.mkdir(parents=True, exist_ok=True)

    default = ["CLIENTES", "MAESTRO", "ARTI2", "facturas", "detafact", "FAMILIAS", "PROVEE", "impuestos", "tarifas", "KARDEX", "ENTRADAS", "AJUSTESINV"]
    selected = set(args.tables) if args.tables else None

    for dbf in sorted(src.glob("*.DBF"), key=lambda p: p.name.lower()):
        if selected is not None and dbf.stem.upper() not in {t.upper() for t in selected}:
            continue
        fields, rows = read_dbf(dbf)
        out = dst / f"{dbf.stem.lower()}.csv"
        write_csv(out, fields, rows)
        print(f"{dbf.name}: {len(rows)} rows -> {out}")


if __name__ == "__main__":
    main()
