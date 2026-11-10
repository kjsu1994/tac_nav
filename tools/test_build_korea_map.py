import unittest
from unittest.mock import patch
from pathlib import Path
import tempfile
import io
from build_korea_map import BOUNDS, CHECKPOINTS, parse_listing, rgb_lookup, download_source, make_vrt

class KoreaMapTests(unittest.TestCase):
    def test_full_peninsula_and_islands_inside_bounds(self):
        for name, (lon, lat) in CHECKPOINTS.items():
            with self.subTest(name=name):
                self.assertTrue(BOUNDS[0] < lon < BOUNDS[2])
                self.assertTrue(BOUNDS[1] < lat < BOUNDS[3])

    def test_source_listing_filters_adjacent_tiles_and_accepts_northern_edge(self):
        def item(tile):
            return f"<Contents><Key>rgbnir/2021/N43/ESA_WorldCover_10m_2021_v200_{tile}_S2RGBNIR.tif</Key><Size>1234</Size></Contents>"
        xml = '<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/"><IsTruncated>false</IsTruncated>'+''.join(item(t) for t in ("N43E130","N43E132","N31E126","N37E131"))+'</ListBucketResult>'
        result = parse_listing(xml)
        self.assertEqual([(43,130),(37,131)],[(x["lat"],x["lon"]) for x in result])
        self.assertTrue(all(x["bytes"]==1234 for x in result))
        with self.assertRaises(ValueError):
            parse_listing(xml.replace("<IsTruncated>false", "<IsTruncated>true"))

    def test_parallel_download_assembles_ranges_and_rejects_wrong_response(self):
        content = b"raster-source-bytes-for-testing"
        class Response(io.BytesIO):
            status = 206
        def response(request, **kwargs):
            start, end = map(int,request.headers["Range"][6:].split("-"))
            result = Response(content[start:end+1])
            result.headers = {"Content-Range":f"bytes {start}-{end}/{len(content)}"}
            return result
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)/"source.tif"
            with patch("urllib.request.urlopen",side_effect=response):
                download_source({"url":"https://example.invalid/source", "bytes":len(content)},output,chunk_size=5)
            self.assertEqual(content,output.read_bytes())
            def invalid(request, **kwargs):
                result = response(request)
                result.status = 200
                return result
            output.unlink()
            with patch("urllib.request.urlopen",side_effect=invalid), patch("time.sleep"):
                with self.assertRaises(ValueError):
                    download_source({"url":"https://example.invalid/source", "bytes":len(content)},output,chunk_size=5)
            self.assertFalse(output.exists())

    def test_vrt_reads_northern_tile_clipped_at_pack_boundary(self):
        import numpy as np
        import rasterio
        from rasterio.transform import from_origin
        from rasterio.windows import Window
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root/"north.tif"
            with rasterio.open(source,"w",driver="GTiff",width=12000,height=12000,count=3,dtype="uint8",crs="EPSG:4326",transform=from_origin(129,44,1/12000,1/12000),nodata=0,tiled=True,compress="deflate",SPARSE_OK=True) as dataset:
                dataset.write(np.full((3,1,1),100,dtype="uint8"),window=Window(2400,10800,1,1))
            point = (129.2+1/24000,43.1-1/24000)
            with patch("build_korea_map.CHECKPOINTS",{"north":point}):
                self.assertEqual({"north":[100,100,100]},make_vrt([source],root/"map.vrt"))

    def test_overviews_preserve_quadrants_tms_rows_and_resume(self):
        import sqlite3
        from PIL import Image
        from build_korea_map import build_overviews
        db = sqlite3.connect(":memory:")
        db.executescript("CREATE TABLE tiles(zoom_level INTEGER,tile_column INTEGER,tile_row INTEGER,tile_data BLOB,PRIMARY KEY(zoom_level,tile_column,tile_row)); CREATE TABLE progress(overview_zoom INTEGER); INSERT INTO progress VALUES(13);")
        colors = [(255,0,0),(0,255,0),(0,0,255),(255,255,255)]
        for index,color in enumerate(colors):
            data = io.BytesIO(); Image.new("RGB",(256,256),color).save(data,format="JPEG",quality=85)
            db.execute("INSERT INTO tiles VALUES(14,?,?,?)",(100+index%2,2**14-1-100-index//2,data.getvalue()))
        build_overviews(db,100,100,102,102)
        data = db.execute("SELECT tile_data FROM tiles WHERE zoom_level=13").fetchone()[0]
        with Image.open(io.BytesIO(data)) as image:
            for index,color in enumerate(colors):
                actual=image.getpixel((64+(index%2)*128,64+(index//2)*128))
                self.assertTrue(all(abs(a-b)<8 for a,b in zip(actual,color)),(actual,color))
        count=db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0]
        build_overviews(db,100,100,102,102)
        self.assertEqual(count,db.execute("SELECT COUNT(*) FROM tiles").fetchone()[0])
        self.assertEqual(-1,db.execute("SELECT overview_zoom FROM progress").fetchone()[0])
        self.assertEqual("ok",db.execute("PRAGMA quick_check").fetchone()[0])
        db.close()

    def test_stretch_is_monotonic_and_preserves_nodata(self):
        lut = rgb_lookup()
        self.assertEqual("uint8",str(lut.dtype))
        self.assertEqual(0,int(lut[0]))
        self.assertEqual(255,int(lut[3000]))
        self.assertEqual(255,int(lut[-1]))
        self.assertTrue(all(int(lut[i])<=int(lut[i+1]) for i in range(65535)))

if __name__ == "__main__":
    unittest.main()
