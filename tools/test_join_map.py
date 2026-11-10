import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

@unittest.skipUnless(sys.platform == "win32" and shutil.which("powershell.exe"), "Windows PowerShell required")
class JoinMapTests(unittest.TestCase):
    def test_round_trip_and_corruption_leave_existing_files_safe(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            content = b"whole-peninsula-map-fixture"
            parts = []
            for index,start in enumerate(range(0,len(content),7)):
                data = content[start:start+7]
                name = f"fixture.part{index:02d}"
                (root/name).write_bytes(data)
                parts.append({"name":name,"bytes":len(data),"sha256":hashlib.sha256(data).hexdigest()})
            manifest = root/"map-parts.json"
            manifest.write_text(json.dumps({"name":"fixture.tacmap","bytes":len(content),"sha256":hashlib.sha256(content).hexdigest(),"parts":parts}),encoding="utf-8")
            def run():
                return subprocess.run(["powershell.exe","-NoProfile","-ExecutionPolicy","Bypass","-File",str(Path(__file__).with_name("Join-Map.ps1")),"-Manifest",str(manifest)],capture_output=True)
            self.assertEqual(0,run().returncode)
            output = root/"fixture.tacmap"
            self.assertEqual(content,output.read_bytes())
            self.assertNotEqual(0,run().returncode)
            self.assertEqual(content,output.read_bytes())
            output.unlink()
            (root/parts[0]["name"]).write_bytes(b"corrupt")
            self.assertNotEqual(0,run().returncode)
            self.assertFalse(output.exists())
            self.assertEqual([],list(root.glob("*.tmp")))

if __name__ == "__main__":
    unittest.main()
