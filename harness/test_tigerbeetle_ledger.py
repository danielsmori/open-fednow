"""Run with the pinned optional SDK; no TigerBeetle server is needed."""
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
import tigerbeetle as tb
from tigerbeetle_ledger import Ledger, UncertainLedgerError, transfer_record


class LedgerResultTest(unittest.TestCase):
    def setUp(self):
        self.ledger = Ledger.__new__(Ledger)
        self.record = transfer_record(1, 2, 3, 70000, tb.TransferFlags.PENDING)

    def test_explicit_insufficient_funds_is_a_refusal(self):
        self.ledger.client = SimpleNamespace(create_transfers=lambda _: [
            SimpleNamespace(status=tb.CreateTransferStatus.EXCEEDS_CREDITS)])
        self.assertEqual(self.ledger._create_transfer(self.record), 'insufficient')

    def test_duplicate_id_requires_matching_ledger_record(self):
        self.ledger.client = SimpleNamespace(create_transfers=lambda _: [
            SimpleNamespace(status=tb.CreateTransferStatus.EXISTS)],
            lookup_transfers=lambda _: [transfer_record(1, 2, 3, 69999,
                tb.TransferFlags.PENDING)])
        with self.assertRaises(UncertainLedgerError):
            self.ledger._create_transfer(self.record)

    def test_ambiguous_error_requires_independent_lookup(self):
        def unavailable(_):
            raise OSError('synthetic lost response')
        self.ledger.client = SimpleNamespace(create_transfers=unavailable,
            lookup_transfers=lambda _: [])
        with self.assertRaises(UncertainLedgerError):
            self.ledger._create_transfer(self.record)
        self.ledger.client.lookup_transfers = lambda _: [self.record]
        self.assertEqual(self.ledger._create_transfer(self.record), 'confirmed')


if __name__ == '__main__':
    unittest.main()
