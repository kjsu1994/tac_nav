"""Fetch the pinned Noto SDF glyphs used by the fully offline map renderer."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import json
from pathlib import Path
import urllib.request

REVISION = '601ae60796ceceda2cbd2ed3d2ea92d17a84be4b'
BASE = f'https://raw.githubusercontent.com/maplibre/demotiles/{REVISION}/font/Noto%20Sans%20Regular/'
ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/assets/fonts'
STARTS = list(range(0,65536,256))
SAVED = json.loads((ROOT/"sources.json").read_text(encoding="utf-8")) if (ROOT/"sources.json").exists() else {}
CACHED = SAVED.get("sha256",{}) if SAVED.get("revision")==REVISION else {}


def fetch(start):
    name = f'{start}-{start+255}.pbf'
    path = ROOT / "Noto Sans Regular" / name
    if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==CACHED.get(name):
        return name,CACHED[name]
    data = urllib.request.urlopen(BASE+name, timeout=60).read()
    if not data.startswith(b'\x0a') or len(data) < 20:
        raise ValueError('Invalid glyph PBF: '+name)
    if 44032 <= start < 55296 and len(data) < 1000:
        raise ValueError('Missing Hangul glyphs: '+name)
    path = ROOT / 'Noto Sans Regular' / name
    temporary = path.with_suffix('.pbf.part')
    temporary.write_bytes(data); temporary.replace(path)
    return name, hashlib.sha256(data).hexdigest()


def main():
    (ROOT/'Noto Sans Regular').mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=6) as pool:
        hashes = dict(pool.map(fetch, STARTS))
    (ROOT/'sources.json').write_text(json.dumps(dict(source=BASE,revision=REVISION,sha256=hashes),indent=2),encoding='utf-8')
    print('Verified bundled glyph ranges:',len(hashes))


if __name__ == '__main__':
    main()
