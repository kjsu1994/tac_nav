import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import zipfile
from pack_map import package
from setup_builtin_map import install
from test_pack_map import fixture


class BuiltInMapTests(unittest.TestCase):
    def make_pack(self, root):
        source=root/'test.pmtiles';fixture(source)
        data=bytearray(source.read_bytes());data[99]=1;source.write_bytes(data)
        pack=root/'test.tacmap'
        manifest=package(source,pack,'Test',0,'2026','OSM','ODbL','protomaps-v4')
        manifest['tiles']['path']='builtin-test.pmtiles'
        target=root/'manifest.json';target.write_text(json.dumps(manifest))
        return source,pack,target

    def test_pinned_payload_is_verified_and_reused(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source,pack,manifest=self.make_pack(root)
            target=install(pack,manifest)
            self.assertEqual(source.read_bytes(),target.read_bytes())
            before=target.stat().st_mtime_ns
            self.assertEqual(target,install(root/'not-needed.tacmap',manifest))
            self.assertEqual(before,target.stat().st_mtime_ns)

    def test_bad_payload_never_replaces_existing_file(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source,pack,manifest=self.make_pack(root)
            existing=root/'builtin-test.pmtiles';existing.write_bytes(b'previous')
            wrong=bytearray(source.read_bytes());wrong[-1]^=1
            with zipfile.ZipFile(root/'wrong.tacmap','w') as archive:
                archive.writestr('manifest.json',json.dumps({'tiles':{'path':'imagery.pmtiles'}}))
                archive.writestr('imagery.pmtiles',wrong)
            with self.assertRaisesRegex(ValueError,'SHA-256'):
                install(root/'wrong.tacmap',manifest)
            self.assertEqual(b'previous',existing.read_bytes())
            self.assertFalse(list(root.glob('.builtin-*')))

    def test_manifest_path_traversal_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory);source,pack,manifest=self.make_pack(root)
            data=json.loads(manifest.read_text());data['tiles']['path']='../outside.pmtiles';manifest.write_text(json.dumps(data))
            with self.assertRaises(ValueError):install(pack,manifest)


if __name__=='__main__':unittest.main()
