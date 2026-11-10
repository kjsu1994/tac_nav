"""Build the whole-peninsula offline imagery pack on a connected PC.
Install tools/requirements-map.txt first. Downloads only official public COGs;
the Android application remains offline. Completed source tiles survive retries.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import math
from pathlib import Path
import sqlite3
import time
import urllib.request
import xml.etree.ElementTree as ET
from xml.sax.saxutils import escape

BOUNDS = (124.0, 32.0, 132.0, 43.2)
BASE = "https://esa-worldcover-s2.s3.eu-central-1.amazonaws.com/"
ATTRIBUTION = "© ESA WorldCover project 2021 / Contains modified Copernicus Sentinel data (2021) processed by ESA WorldCover consortium"
LICENSE = "CC BY 4.0 https://creativecommons.org/licenses/by/4.0/"
NS = {"s": "http://s3.amazonaws.com/doc/2006-03-01/"}
CHECKPOINTS = {
    "Seoul": (126.978, 37.5665), "Busan": (129.0756, 35.1796),
    "Pyongyang": (125.7625, 39.0392), "Sinuiju": (124.402, 40.1),
    "Rason": (130.301, 42.255), "Jeju": (126.531, 33.4996),
    "Marado": (126.269, 33.117), "Ulleungdo": (130.868, 37.484),
    "Dokdo": (131.867, 37.242), "Baengnyeongdo": (124.668, 37.966),
}


def parse_listing(data, bounds=BOUNDS):
    root = ET.fromstring(data)
    if root.findtext("s:IsTruncated", "false", NS) != "false":
        raise ValueError("Source listing was truncated")
    result = []
    for item in root.findall("s:Contents", NS):
        key = item.findtext("s:Key", "", NS)
        tile = key.split("_")[-2] if key.endswith("_S2RGBNIR.tif") else ""
        if len(tile) != 7 or tile[0] != "N" or tile[3] != "E":
            continue
        lat, lon = int(tile[1:3]), int(tile[4:])
        if lon < bounds[2] and lon + 1 > bounds[0] and lat < bounds[3] and lat + 1 > bounds[1]:
            result.append(dict(key=key, url=BASE + key, bytes=int(item.findtext("s:Size", "0", NS)), lat=lat, lon=lon))
    return result


def sources():
    def row(lat):
        url = BASE + "?list-type=2&prefix=rgbnir/2021/N" + str(lat) + "/"
        with urllib.request.urlopen(url, timeout=90) as response:
            return parse_listing(response.read())
    with ThreadPoolExecutor(max_workers=4) as pool:
        result = [item for row_items in pool.map(row, range(math.floor(BOUNDS[1]), math.ceil(BOUNDS[3]))) for item in row_items]
    if not result:
        raise ValueError("No public imagery sources found")
    return sorted(result, key=lambda item: (item["lat"], item["lon"]))


def rgb_lookup():
    import numpy as np
    # Fixed reflectance stretch preserves relative brightness across tile seams.
    lut = (np.clip(np.arange(65536) / 3000.0, 0, 1) ** (1 / 1.8) * 254 + 1).astype("uint8")
    lut[0] = 0
    return lut


def download_source(item, output, chunk_size=8 * 1024**2):
    temporary = output.with_suffix(".download")
    def fetch(start):
        end = min(start + chunk_size, item["bytes"]) - 1
        expected = end - start + 1
        request = urllib.request.Request(item["url"], headers={"Range": f"bytes={start}-{end}"})
        for attempt in range(3):
            try:
                with urllib.request.urlopen(request, timeout=90) as response:
                    if response.status != 206 or response.headers.get("Content-Range") != f"bytes {start}-{end}/{item['bytes']}":
                        raise ValueError("Source server did not honor the requested byte range")
                    data = response.read(expected + 1)
                if len(data) != expected:
                    raise ValueError("Incomplete source byte range")
                return start, data
            except (OSError, ValueError):
                if attempt == 2:
                    raise
                time.sleep(2)
    with temporary.open("wb") as stream, ThreadPoolExecutor(max_workers=4) as pool:
        futures = {pool.submit(fetch,start) for start in range(0,item["bytes"],chunk_size)}
        for future in as_completed(futures):
            start, data = future.result()
            stream.seek(start)
            stream.write(data)
            futures.remove(future)
    if temporary.stat().st_size != item["bytes"]:
        raise ValueError("Incomplete source download")
    temporary.replace(output)


def prepare_tile(item, cache):
    import numpy as np
    import rasterio
    from rasterio.windows import Window
    output = cache / (Path(item["key"]).stem + "_rgb.tif")
    if output.exists():
        with rasterio.open(output) as dataset:
            assert dataset.count == 3 and dataset.width == dataset.height == 12000
        print("Cached " + output.name, flush=True)
        return output
    temporary = output.with_suffix(".part.tif")
    lookup = rgb_lookup()
    for attempt in range(3):
        try:
            local_source = cache / (Path(item["key"]).stem + ".source.tif")
            if not local_source.exists() or local_source.stat().st_size != item["bytes"]:
                download_source(item, local_source)
                print("Downloaded " + local_source.name, flush=True)
            with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR", CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif", GDAL_HTTP_TIMEOUT="120", GDAL_HTTP_MAX_RETRY="3", GDAL_HTTP_RETRY_DELAY="2", GDAL_CACHEMAX=128 * 1024**2):
                with rasterio.open(local_source) as src:
                    assert src.width == src.height == 12000 and src.count == 4 and src.crs.to_epsg() == 4326
                    profile = src.profile.copy()
                    profile.update(driver="GTiff", count=3, dtype="uint8", nodata=0, compress="deflate", predictor=2, tiled=True, blockxsize=512, blockysize=512, BIGTIFF="IF_SAFER")
                    with rasterio.open(temporary, "w", **profile) as dst:
                        for y in range(0, src.height, 1024):
                            window = Window(0, y, src.width, min(1024, src.height - y))
                            raw = src.read([1, 2, 3], window=window)
                            rgb = lookup[raw]
                            rgb[:, np.all(raw == 0, axis=0)] = 0
                            dst.write(rgb, window=window)
            temporary.replace(output)
            local_source.unlink()
            print("Prepared " + output.name + " " + str(output.stat().st_size), flush=True)
            return output
        except Exception:
            temporary.unlink(missing_ok=True)
            if attempt == 2:
                raise
            time.sleep(2 * (attempt + 1))
    raise AssertionError("unreachable")


def make_vrt(paths, output):
    import rasterio
    west, south, east, north = BOUNDS
    width, height = round((east - west) * 12000), round((north - south) * 12000)
    text = [f'<VRTDataset rasterXSize="{width}" rasterYSize="{height}"><SRS>EPSG:4326</SRS><GeoTransform>{west},0.00008333333333333333,0,{north},0,-0.00008333333333333333</GeoTransform>']
    offsets = []
    for path in paths:
        with rasterio.open(path) as src:
            offsets.append((path, round((src.bounds.left - west) * 12000), round((north - src.bounds.top) * 12000)))
    for band in range(1, 4):
        text.append(f'<VRTRasterBand dataType="Byte" band="{band}"><NoDataValue>0</NoDataValue><ColorInterp>{("Red", "Green", "Blue")[band-1]}</ColorInterp>')
        for path, x, y in offsets:
            text.append(f'<ComplexSource><SourceFilename relativeToVRT="0">{escape(str(path.resolve()))}</SourceFilename><SourceBand>{band}</SourceBand><SrcRect xOff="0" yOff="0" xSize="12000" ySize="12000"/><DstRect xOff="{x}" yOff="{y}" xSize="12000" ySize="12000"/><NODATA>0</NODATA></ComplexSource>')
        text.append('</VRTRasterBand>')
    text.append('</VRTDataset>')
    output.write_text("".join(text), encoding="utf-8")
    with rasterio.open(output) as src:
        coverage = {}
        for name, point in CHECKPOINTS.items():
            pixel = next(src.sample([point]))
            coverage[name] = [int(value) for value in pixel]
            if not any(pixel):
                raise ValueError(f"Missing imagery at coverage checkpoint: {name}")
    return coverage


def render(vrt, mbtiles):
    import rasterio
    from rasterio.transform import from_origin
    from rasterio.warp import Resampling, transform_bounds
    from rasterio.vrt import WarpedVRT
    from rasterio.windows import Window
    from concurrent.futures import wait, FIRST_COMPLETED
    import threading
    left, bottom, right, top = transform_bounds("EPSG:4326", "EPSG:3857", *BOUNDS)
    world = 2 * math.pi * 6378137
    resolution = world / (256 * 2**14)
    x0 = math.floor((left + world/2) / resolution / 256)
    y0 = math.floor((world/2 - top) / resolution / 256)
    x1 = math.ceil((right + world/2) / resolution / 256)
    y1 = math.ceil((world/2 - bottom) / resolution / 256)
    width, height = (x1-x0)*256, (y1-y0)*256
    transform = from_origin(-world/2+x0*256*resolution,world/2-y0*256*resolution,resolution,resolution)
    from PIL import Image
    import io
    temporary = mbtiles.with_suffix(".tiles.part.mbtiles")
    signature = hashlib.sha256((vrt.read_text(encoding="utf-8")+str(BOUNDS)+"jpeg85-bilinear-v1").encode()).hexdigest()
    db = sqlite3.connect(temporary)
    db.execute("PRAGMA synchronous=FULL")
    db.execute("PRAGMA journal_mode=DELETE")
    db.execute("PRAGMA cache_size=-65536")
    db.executescript("CREATE TABLE IF NOT EXISTS metadata(name TEXT PRIMARY KEY,value TEXT); CREATE TABLE IF NOT EXISTS tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB,PRIMARY KEY(zoom_level,tile_column,tile_row)); CREATE TABLE IF NOT EXISTS progress(signature TEXT,next_y INTEGER,overview_zoom INTEGER);")
    state = db.execute("SELECT signature,next_y,overview_zoom FROM progress").fetchone()
    if state is None:
        db.execute("INSERT INTO progress VALUES(?,0,13)",(signature,))
        metadata = dict(name="Korean Peninsula ESA WorldCover 2021",format="jpeg",bounds=','.join(map(str,BOUNDS)),minzoom="0",maxzoom="14",type="baselayer",version="1.3")
        db.executemany("INSERT INTO metadata VALUES(?,?)",metadata.items()); db.commit()
        next_y = 0
    else:
        if state[0] != signature: raise ValueError("Intermediate map belongs to different sources or render settings")
        next_y = state[1]
        assert db.execute("PRAGMA quick_check").fetchone()[0] == "ok"
    print(f"Rendering {width} x {height} pixels; resuming at row {next_y}/{height}",flush=True)
    local = threading.local()
    resources = []
    def read(window):
        if not hasattr(local,"warped"):
            source = rasterio.open(vrt)
            local.warped = WarpedVRT(source,crs="EPSG:3857",transform=transform,width=width,height=height,resampling=Resampling.bilinear,warp_mem_limit=64)
            resources.append((local.warped,source))
        return window, local.warped.read(window=window)
    try:
        with rasterio.Env(GDAL_CACHEMAX=768*1024**2), ThreadPoolExecutor(max_workers=4) as pool:
            for y in range(next_y,height,2048):
                windows = iter(Window(x,y,min(2048,width-x),min(2048,height-y)) for x in range(0,width,2048))
                pending = {pool.submit(read,w) for _,w in zip(range(4),windows)}
                while pending:
                    done,pending = wait(pending,return_when=FIRST_COMPLETED)
                    for future in done:
                        window,pixels = future.result()
                        image = Image.fromarray(pixels.transpose(1,2,0))
                        records = []
                        for dy in range(0,image.height,256):
                            for dx in range(0,image.width,256):
                                encoded = io.BytesIO()
                                image.crop((dx,dy,dx+256,dy+256)).save(encoded,format="JPEG",quality=85)
                                records.append((14,x0+(int(window.col_off)+dx)//256,2**14-1-y0-(y+dy)//256,encoded.getvalue()))
                        db.executemany("INSERT OR REPLACE INTO tiles VALUES(?,?,?,?)",records)
                        next_window = next(windows,None)
                        if next_window is not None: pending.add(pool.submit(read,next_window))
                db.execute("UPDATE progress SET next_y=?",(min(y+2048,height),)); db.commit()
                print(f"Saved rows {min(y+2048,height)}/{height}",flush=True)
        for warped,source in resources:
            warped.close(); source.close()
        resources.clear()
        build_overviews(db,x0,y0,x1,y1)
        assert db.execute("PRAGMA quick_check").fetchone()[0] == "ok"
        count = db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
        assert count > 10000
    finally:
        db.close()
        for warped,source in resources:
            warped.close(); source.close()
    temporary.replace(mbtiles)
    print(f"Rendered {count} tiles / {mbtiles.stat().st_size} bytes",flush=True)


def build_overviews(db,x0,y0,x1,y1):
    from PIL import Image
    import io
    start = db.execute("SELECT overview_zoom FROM progress").fetchone()[0]
    for zoom in range(start,-1,-1):
        scale = 2**(14-zoom)
        for y in range(y0//scale,math.ceil(y1/scale)):
            for x in range(x0//scale,math.ceil(x1/scale)):
                image = Image.new("RGB",(512,512))
                for dy in range(2):
                    for dx in range(2):
                        row = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=? AND tile_column=? AND tile_row=?",(zoom+1,x*2+dx,2**(zoom+1)-1-y*2-dy)).fetchone()
                        if row:
                            with Image.open(io.BytesIO(row[0])) as tile: image.paste(tile,(dx*256,dy*256))
                encoded = io.BytesIO()
                image.resize((256,256),Image.Resampling.BOX).save(encoded,format="JPEG",quality=85)
                db.execute("INSERT OR REPLACE INTO tiles VALUES(?,?,?,?)",(zoom,x,2**zoom-1-y,encoded.getvalue()))
            db.commit()
        db.execute("UPDATE progress SET overview_zoom=?",(zoom-1,));db.commit()
        print(f"Saved overview zoom {zoom}",flush=True)


def verify_checkpoint_tiles(pmtiles):
    from pmtiles.reader import Reader
    import rasterio
    from rasterio.io import MemoryFile
    from rasterio.windows import Window
    with pmtiles.open("rb") as stream:
        def read_at(offset, length):
            stream.seek(offset)
            return stream.read(length)
        reader = Reader(read_at)
        assert reader.header()["min_zoom"] == 0 and reader.header()["max_zoom"] == 14
        for name, (lon, lat) in CHECKPOINTS.items():
            for zoom in (0,5,10,14):
                x = (lon + 180) / 360 * (1 << zoom)
                y = (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * (1 << zoom)
                data = reader.get(zoom,int(x),int(y))
                if not data or not data.startswith(b"\xff\xd8"):
                    raise ValueError(f"Missing JPEG tile at {name} / zoom {zoom}")
                if zoom == 14:
                    with MemoryFile(data) as memory, memory.open() as tile:
                        pixel = tile.read(window=Window(int(x % 1 * 256),int(y % 1 * 256),1,1))
                        if int(pixel.max()) < 5:
                            raise ValueError("Empty output imagery at " + name)
    print("Verified all checkpoint tiles at zooms 0,5,10,14",flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path)
    parser.add_argument("--cache", type=Path, default=Path(".map-cache"))
    parser.add_argument("--workers", type=int, choices=range(1,9), default=4)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output exists; choose a new filename")
    args.cache.mkdir(parents=True, exist_ok=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    import shutil
    if shutil.disk_usage(args.cache).free < 20 * 1024**3:
        parser.error("At least 20 GiB free disk space required")
    items = sources()
    (args.cache / "sources.json").write_text(json.dumps(items, indent=2), encoding="utf-8")
    print(f"Public source tiles: {len(items)}, original bytes: {sum(item['bytes'] for item in items)}", flush=True)
    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        paths = list(pool.map(lambda item: prepare_tile(item,args.cache), items))
    vrt, mbtiles, pmtiles = (args.cache / ("korea." + suffix) for suffix in ("vrt", "mbtiles", "pmtiles"))
    coverage = make_vrt(paths, vrt)
    if not mbtiles.exists():
        render(vrt, mbtiles)
    from pmtiles.convert import mbtiles_to_pmtiles
    from pack_map import package
    if not pmtiles.exists():
        temporary = pmtiles.with_suffix(".part.pmtiles")
        mbtiles_to_pmtiles(str(mbtiles),str(temporary),None)
        temporary.replace(pmtiles)
    verify_checkpoint_tiles(pmtiles)
    manifest = package(pmtiles, args.output, "한반도 전체 · ESA 2021 연간 합성영상", 10.0, "2021-01-01/2021-12-31", ATTRIBUTION, LICENSE)
    with args.output.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    args.output.with_suffix(args.output.suffix+".sha256").write_text(digest+"  "+args.output.name+"\n",encoding="utf-8")
    provenance = {"manifest":manifest,"requestedBounds":BOUNDS,"sources":items,"coverageCheckpoints":coverage,"displayStretch":{"reflectanceWhite":3000,"gamma":1.8},"note":"Annual median composite; not a single acquisition or current operational imagery. Zero/nodata areas have no observations; geographic bounds do not guarantee observation at every pixel."}
    args.output.with_suffix(".json").write_text(json.dumps(provenance,ensure_ascii=False,indent=2),encoding="utf-8")
    print(f"COMPLETE {args.output} {args.output.stat().st_size} bytes SHA256 {digest}",flush=True)


if __name__ == "__main__":
    main()
