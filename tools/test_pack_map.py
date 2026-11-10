import json
from pathlib import Path
import struct
import tempfile
import unittest
import zipfile
from pack_map import describe, package


def fixture(path):
    # Structural test fixture only, deliberately not a displayable geographical map.
    header = bytearray(127)
    header[:8] = b"PMTiles\x03"
    struct.pack_into("<QQ", header, 8, 127, 1)
    struct.pack_into("<QQ", header, 56, 128, 1)
    header[97:102] = bytes([1, 1, 3, 5, 14])
    struct.pack_into("<4i", header, 102, 1260000000, 370000000, 1270000000, 380000000)
    path.write_bytes(header + b"\0\0")


class PackMapTests(unittest.TestCase):
    def test_header_bounds(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.pmtiles"
            fixture(path)
            self.assertEqual(describe(path), (129, 5, 14, [126.0, 37.0, 127.0, 38.0]))

    def test_package_contains_only_manifest_and_tiles(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.pmtiles"
            output = Path(directory) / "test.tacmap"
            fixture(path)
            manifest = package(path, output, "TEST ONLY", 10.0, "2026-01-01", "TEST DATA", "NOT FOR NAVIGATION")
            with zipfile.ZipFile(output) as archive:
                self.assertEqual(set(archive.namelist()), {"manifest.json", "imagery.pmtiles"})
                self.assertEqual(json.loads(archive.read("manifest.json")), manifest)
                self.assertEqual(archive.read("imagery.pmtiles"), path.read_bytes())
            with self.assertRaises(ValueError):
                package(path, output, "TEST ONLY", 10.0, "2026", "TEST", "TEST")

    def test_rejects_vector_and_truncated_files(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.pmtiles"
            fixture(path)
            data = bytearray(path.read_bytes())
            data[99] = 1
            path.write_bytes(data)
            with self.assertRaises(ValueError):
                describe(path)
            path.write_bytes(b"PMTiles\x03")
            with self.assertRaises(ValueError):
                describe(path)

    def test_vector_requires_explicit_kind_and_zero_resolution(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "vector.pmtiles"
            fixture(path)
            data = bytearray(path.read_bytes()); data[99] = 1; path.write_bytes(data)
            output = Path(directory) / "vector.tacmap"
            manifest = package(path, output, "Vector", 0, "2026", "OSM", "ODbL", "protomaps-v4")
            self.assertEqual(manifest["kind"], "protomaps-v4")
            for kind in ("raster", "untrusted-style"):
                with self.assertRaises(ValueError):
                    describe(path, kind)
            with self.assertRaises(ValueError):
                package(path, Path(directory)/"bad.tacmap", "Vector", 10, "2026", "OSM", "ODbL", "protomaps-v4")

    def test_rejects_out_of_file_sections(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "test.pmtiles"
            fixture(path)
            data = bytearray(path.read_bytes())
            struct.pack_into("<Q", data, 56, 999999)
            path.write_bytes(data)
            with self.assertRaises(ValueError):
                describe(path)


if __name__ == "__main__":
    unittest.main()
