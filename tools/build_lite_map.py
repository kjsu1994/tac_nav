"""Extract an offline Korean peninsula Protomaps v4 map using the official pmtiles CLI."""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import subprocess
from pack_map import package


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--pmtiles', default='pmtiles', help='Official go-pmtiles executable')
    parser.add_argument('--date', default=dt.date.today().isoformat(), help='Available Protomaps build date YYYY-MM-DD')
    parser.add_argument('--cache', type=Path, default=Path('.map-cache/korea-lite.pmtiles'))
    args = parser.parse_args()
    args.cache.parent.mkdir(parents=True, exist_ok=True)
    url = 'https://build.protomaps.com/' + dt.date.fromisoformat(args.date).strftime('%Y%m%d') + '.pmtiles'
    if not args.cache.exists():
        subprocess.run([args.pmtiles,'extract',url,str(args.cache),'--bbox=124,32,132,43.2','--maxzoom=13','--download-threads=4'],check=True)
    subprocess.run([args.pmtiles,'verify',str(args.cache)],check=True)
    metadata = json.loads(subprocess.check_output([args.pmtiles,'show',str(args.cache),'--metadata']))
    if not metadata.get('version','').startswith('4.'):
        raise ValueError('Requires Protomaps v4 schema')
    manifest = package(args.cache,args.output,'한반도 경량 2D · 도로·지명',0,
        metadata['planetiler:osm:osmosisreplicationtime'],
        '© OpenStreetMap contributors · Natural Earth · Protomaps',
        'ODbL produced work · https://www.openstreetmap.org/copyright','protomaps-v4')
    assert manifest['bounds'] == [124.0,32.0,132.0,43.2] and manifest['maxZoom'] == 13
    with args.output.open('rb') as stream:
        digest = hashlib.file_digest(stream,'sha256').hexdigest()
    args.output.with_suffix(args.output.suffix+'.sha256').write_text(digest+'  '+args.output.name+'\n',encoding='utf-8')
    info = dict(manifest=manifest,sourceUrl=url,metadata=metadata,packBytes=args.output.stat().st_size,packSha256=digest)
    args.output.with_suffix('.json').write_text(json.dumps(info,ensure_ascii=False,indent=2),encoding='utf-8')
    print('COMPLETE',args.output,args.output.stat().st_size,digest)

if __name__ == '__main__':
    main()
