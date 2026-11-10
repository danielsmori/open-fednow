"""Checks for local-only ledger selection and pending-payment identity handling."""
import subprocess
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from reference_target import local_tigerbeetle_address, prior_payment_status


class ReferenceTargetGuardTest(unittest.TestCase):
    def test_rejects_replica_lists_and_invalid_ports_before_sdk_connection(self):
        script = Path(__file__).resolve().parent / 'reference_target.py'
        for address in ('127.0.0.1:3000,10.0.0.5:3000', '127.0.0.1:0',
                        '127.0.0.1:65536', 'localhost:3000',
                        '127.0.0.1:3000,127.0.0.1:3001'):
            with self.subTest(address=address):
                with self.assertRaises(ValueError):
                    local_tigerbeetle_address(address)
                result = subprocess.run([sys.executable, str(script), '--db', ':memory:',
                    '--rail-url', 'http://127.0.0.1:1', '--tigerbeetle-address', address],
                    capture_output=True, text=True, timeout=3)
                self.assertEqual(result.returncode, 2)
        self.assertEqual(local_tigerbeetle_address('127.0.0.1:3000'), '127.0.0.1:3000')
        self.assertEqual(local_tigerbeetle_address('127.0.0.1:65535'), '127.0.0.1:65535')

    def test_conflicting_payload_stays_conflict_while_reservation_is_pending(self):
        self.assertEqual(prior_payment_status('original', 'changed', 'RESERVING', True), 409)
        self.assertEqual(prior_payment_status('original', 'original', 'RESERVING', True), 503)
        self.assertEqual(prior_payment_status('original', 'original', 'SETTLED', True), 200)


if __name__ == '__main__':
    unittest.main()
