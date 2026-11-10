"""Prepare the pinned map embedded in the APK. End users only install the APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import tempfile
import urllib.request
import zipfile
from pack_map import describe, MAX_BYTES

ROOT = Path(__file__).resolve().parents[1]
MANIFEST = ROOT / 'app/src/main/assets/maps/manifest.json'
URL = 'https://github.com/kjsu1994/tac_nav/releases/download/v0.1.0-dev/Korean_Peninsula_OSM_2026_2D.tacmap'


def expected_map(manifest_path):
    manifest = json.loads(Path(manifest_path).read_text(encoding='utf-8'))
    tiles = manifest['tiles']
    if manifest['version'] != 1 or manifest['kind'] != 'protomaps-v4':
        raise ValueError('Expected a Protomaps v4 map manifest')
    if not re.fullmatch(r'[A-Za-z0-9_-]+\.pmtiles', tiles['path']):
        raise ValueError('Invalid bundled map filename')
    if not 127 <= tiles['bytes'] <= MAX_BYTES or not re.fullmatch('[0-9a-f]{64}', tiles['sha256']):
        raise ValueError('Invalid pinned map size/hash')
    return manifest, Path(manifest_path).parent / tiles['path']


def matches(path, tiles):
    if not path.is_file() or path.stat().st_size != tiles['bytes']:
        return False
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest() == tiles['sha256']


def install(pack, manifest_path=MANIFEST):
    manifest, target = expected_map(manifest_path)
    tiles = manifest['tiles']
    if matches(target, tiles):
        return target
    handle, name = tempfile.mkstemp(prefix='.builtin-', dir=target.parent)
    os.close(handle)
    temporary = Path(name)
    try:
        with zipfile.ZipFile(pack) as archive:
            info = archive.getinfo('manifest.json')
            if info.file_size > 65536:
                raise ValueError('Oversized source manifest')
            source = json.loads(archive.read(info))['tiles']['path']
            if not re.fullmatch(r'[A-Za-z0-9_-]+\.pmtiles', source) or len(archive.namelist()) != 2 or set(archive.namelist()) != {'manifest.json', source}:
                raise ValueError('Invalid source archive entries')
            if archive.getinfo(source).file_size != tiles['bytes']:
                raise ValueError('Map size differs from pinned manifest')
            with archive.open(source) as stream, temporary.open('wb') as output:
                total = 0
                while block := stream.read(1024 * 1024):
                    total += len(block)
                    if total > tiles['bytes']:
                        raise ValueError('Map exceeds pinned size')
                    output.write(block)
                output.flush(); os.fsync(output.fileno())
        if not matches(temporary, tiles):
            raise ValueError('Bundled map SHA-256 mismatch')
        size, low, high, bounds = describe(temporary, 'protomaps-v4')
        if (low, high, bounds) != (manifest['minZoom'], manifest['maxZoom'], manifest['bounds']):
            raise ValueError('Bundled map header differs from manifest')
        os.replace(temporary, target)
        return target
    finally:
        temporary.unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pack', nargs='?', type=Path, help='Existing local .tacmap; otherwise download the pinned release')
    args = parser.parse_args()
    try:
        manifest, target = expected_map(MANIFEST)
        if matches(target, manifest['tiles']):
            print('Bundled map already verified:', target)
            return
        if args.pack:
            result = install(args.pack)
        else:
            cache = ROOT / '.map-cache'; cache.mkdir(exist_ok=True)
            with tempfile.TemporaryDirectory(prefix='builtin-', dir=cache) as directory:
                download = Path(directory) / 'map.tacmap'
                with urllib.request.urlopen(URL, timeout=120) as stream, download.open('wb') as output:
                    total = 0
                    while block := stream.read(1024 * 1024):
                        total += len(block)
                        if total > manifest['tiles']['bytes'] + 131072:
                            raise ValueError('Download exceeds pinned size')
                        output.write(block)
                result = install(download)
        print('Bundled map verified:', result)
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        parser.exit(1, f'Error: {error}\n')


if __name__ == '__main__':
    main()
