import importlib.util
import sys
import unittest
from pathlib import Path


MODULE_PATH = Path(__file__).parents[1] / "mac_ble_throttle_discovery.py"
SPEC = importlib.util.spec_from_file_location("mac_ble_throttle_discovery", MODULE_PATH)
module = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
sys.modules[SPEC.name] = module
SPEC.loader.exec_module(module)


class MacBleThrottleDiscoveryTest(unittest.TestCase):
    def test_parses_spaced_dlc_and_compact_can_frames(self):
        frames = module.parse_can_frames(
            "18DAF110 8 01 02 03 04 05 06 07 08\r"
            "123 11 22 33 44\r"
            "18ABCDEF0A0B0C0D\rBUFFER FULL\r>"
        )
        self.assertEqual(
            [("18DAF110", "0102030405060708"), ("123", "11223344"), ("18ABCDEF", "0A0B0C0D")],
            [(frame.can_id, frame.payload.hex().upper()) for frame in frames],
        )

    def test_ranks_repeatable_pedal_shaped_word(self):
        def phase(value, noise):
            frames = []
            for delta in noise:
                payload = (value + delta).to_bytes(2, "big") + bytes((40 + delta, 0xAA))
                frames.append(module.CanFrame("18ABCDEF", payload))
            return frames

        phases = {
            "BOS": phase(100, (-1, 0, 0, 1, 0)),
            "YUZDE25": phase(1000, (-1, 0, 1, 0, 0)),
            "YUZDE50": phase(2000, (-1, 0, 1, 0, 0)),
            "BIRAK": phase(101, (-1, 0, 1, 0, 0)),
        }
        candidates = module.rank_candidates(phases)
        self.assertTrue(any(
            item.can_id == "18ABCDEF" and item.start_byte == 0
            and item.width == 2 and item.byte_order == "big"
            for item in candidates
        ))

    def test_flat_signal_is_not_a_candidate(self):
        frames = [module.CanFrame("123", b"\x10\x20") for _ in range(4)]
        self.assertEqual([], module.rank_candidates({name: frames for name, _ in module.PHASES}))

    def test_derives_known_becm_context(self):
        self.assertEqual(("D01635", "1EC6AE80", "1DD01635"), module.derive_ecu(0x1635))


if __name__ == "__main__":
    unittest.main()
