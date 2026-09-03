import importlib.util
import struct
import sys
import tempfile
import unittest
from pathlib import Path

MODULE_PATH = Path(__file__).parents[1] / "extract_hci_profile.py"
SPEC = importlib.util.spec_from_file_location("extract_hci_profile", MODULE_PATH)
module = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


def snoop_record(payload: bytes, flags: int, timestamp: int) -> bytes:
    return struct.pack(">IIIIQ", len(payload), len(payload), flags, 0, timestamp) + payload


class HciProfileTest(unittest.TestCase):
    def test_extracts_context_and_redacts_vin(self):
        data = b"btsnoop\0" + struct.pack(">II", 1, 1002)
        packets = [
            (b"\x02noiseATSP7\r", 0), (b"\x02OK\r>", 1),
            (b"\x02ATSHD01635\r", 0), (b"\x02OK\r>", 1),
            (b"\x02ATCRA1EC6AE80\r", 0), (b"\x02OK\r>", 1),
            (b"\x02224801\r", 0), (b"\x02624801A73A\r>", 1),
            (b"\x0222F190\r", 0), (b"\x0262F190SECRET-VIN\r>", 1),
            (b"\x022E1234FFFF\r", 0), (b"\x026E1234\r>", 1),
        ]
        for index, (payload, flags) in enumerate(packets):
            data += snoop_record(payload, flags, index + 1)
        records = module.parse_records(data)
        profile = module.extract_profile(records, "sample.log")
        self.assertEqual(["4801", "F190"], [query["did"] for query in profile["queries"]])
        self.assertEqual("D01635", profile["queries"][0]["ecu"]["header"])
        self.assertEqual("[REDACTED]", profile["queries"][1]["observedResponse"])

    def test_loads_bugreport_zip(self):
        data = b"btsnoop\0" + struct.pack(">II", 1, 1002)
        with tempfile.TemporaryDirectory() as directory:
            archive_path = Path(directory) / "bugreport.zip"
            import zipfile
            with zipfile.ZipFile(archive_path, "w") as archive:
                archive.writestr("FS/data/misc/bluetooth/logs/btsnoop_hci.log", data)
            self.assertEqual(data, module.load_snoop(archive_path))


if __name__ == "__main__":
    unittest.main()
