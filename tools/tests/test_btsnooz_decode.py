import io
import struct
import unittest

from tools.btsnooz_decode import TYPE_IN_ACL, TYPE_OUT_ACL, decode_snooz_v2
from tools.extract_hci_profile import parse_records


class BtsnoozDecodeTest(unittest.TestCase):
    def test_v2_preserves_packet_lengths_directions_and_payloads(self) -> None:
        outbound = b"\x01\x02\x03"
        inbound = b"\x04\x05"
        decompressed = b"".join(
            (
                struct.pack("=HHIb", len(outbound) + 1, 8, 10, TYPE_OUT_ACL) + outbound,
                struct.pack("=HHIb", len(inbound) + 1, 6, 20, TYPE_IN_ACL) + inbound,
            )
        )
        output = io.BytesIO()
        output.write(b"btsnoop\0\0\0\0\1\0\0\3\xea")

        decode_snooz_v2(decompressed, 1_000, output)
        records = parse_records(output.getvalue())

        self.assertEqual([record.packet for record in records], [b"\x02" + outbound, b"\x02" + inbound])
        self.assertEqual([record.direction for record in records], [0, 1])
        self.assertEqual(
            [struct.unpack_from(">I", output.getvalue(), offset)[0] for offset in (16, 44)],
            [8, 6],
        )


if __name__ == "__main__":
    unittest.main()
