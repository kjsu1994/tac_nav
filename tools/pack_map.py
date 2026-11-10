"""Package an existing raster or Protomaps v4 vector PMTiles v3 file for Tac_Nav. Standard library only."""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import tempfile
import zipfile

MAX_BYTES = 16 * 1024**3

def describe(path, kind="raster"):
    path = Path(path)
    size = path.stat().st_size
    if not 127 <= size <= MAX_BYTES:
        raise ValueError("PMTiles must be between 127 bytes and 16 GiB")
    with path.open("rb") as stream:
        header = stream.read(127)
    if kind not in ("raster", "protomaps-v4") or header[:8] != b"PMTiles\x03" or header[99] not in ((1,) if kind == "protomaps-v4" else (2, 3, 4)):
        raise ValueError("PMTiles v3 tile type does not match requested map kind")
    for offset_at, length_at, required in ((8, 16, True), (24, 32, False), (40, 48, False), (56, 64, True)):
        offset, length = struct.unpack_from("<Q", header, offset_at)[0], struct.unpack_from("<Q", header, length_at)[0]
        if (required and length == 0) or (length and (offset < 127 or offset + length > size)):
            raise ValueError("PMTiles section lies outside file")
    min_zoom, max_zoom = header[100], header[101]
    bounds = [value / 1e7 for value in struct.unpack_from("<4i", header, 102)]
    if not (0 <= min_zoom <= max_zoom <= 22 and -180 <= bounds[0] < bounds[2] <= 180 and -80 <= bounds[1] < bounds[3] <= 84):
        raise ValueError("Invalid or unsupported PMTiles bounds/zoom")
    return size, min_zoom, max_zoom, bounds

def package(source, output, name, resolution, captured, attribution, license_text, kind="raster"):
    source, output = Path(source), Path(output)
    if output.exists():
        raise ValueError("Output already exists; choose a new filename")
    if not name.strip() or not attribution.strip() or not license_text.strip() or not captured.strip():
        raise ValueError("Name, source, capture date/range and license are required")
    if not (0 < resolution < float("inf") if kind == "raster" else resolution == 0):
        raise ValueError("Resolution must be positive and finite")
    size, min_zoom, max_zoom, bounds = describe(source, kind)
    if shutil.disk_usage(output.parent).free < size + 64 * 1024**2:
        raise ValueError("Not enough disk space for output")
    digest = hashlib.sha256()
    with source.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    manifest = {
        "version": 1, "kind": kind, "name": name, "bounds": bounds,
        "minZoom": min_zoom, "maxZoom": max_zoom,
        "resolutionMeters": resolution, "captured": captured,
        "produced": dt.date.today().isoformat(), "source": attribution,
        "license": license_text,
        "tiles": {"path": "imagery.pmtiles", "bytes": size, "sha256": digest.hexdigest()},
    }
    handle, temporary = tempfile.mkstemp(prefix=".tacmap-", dir=output.parent)
    os.close(handle)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_STORED, allowZip64=True) as archive:
            archive.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
            archive.write(source, "imagery.pmtiles")
        # Atomic publication; never overwrite an existing user's package.
        if os.name == "nt":
            os.rename(temporary, output)
        else:
            os.link(temporary, output)
    finally:
        Path(temporary).unlink(missing_ok=True)
    return manifest

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--kind", choices=("raster", "protomaps-v4"), default="raster")
    parser.add_argument("--resolution", type=float, required=True, help="Native image resolution in metres/pixel")
    parser.add_argument("--captured", required=True, help="ISO capture date or date range")
    parser.add_argument("--source", required=True, help="Visible source attribution")
    parser.add_argument("--license", required=True)
    args = parser.parse_args()
    try:
        manifest = package(args.input, args.output, args.name, args.resolution, args.captured, args.source, args.license, args.kind)
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        parser.exit(1, f"Error: {error}\n")
    print(json.dumps(manifest, ensure_ascii=False, indent=2))

if __name__ == "__main__":
    main()
