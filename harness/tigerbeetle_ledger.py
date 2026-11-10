"""Optional synthetic TigerBeetle ledger for the Python reference target.

Only the local experiment imports this module. The default SQLite target has no
TigerBeetle dependency. IDs are deterministic within the run's random fixture IDs.
"""
import hashlib

import tigerbeetle as tb


LEDGER = 840  # Synthetic USD minor units; not a bank account identifier.
CODE = 1


def identity(value):
    result = int.from_bytes(hashlib.sha256(value.encode()).digest()[:16], "big")
    return result if result not in (0, (1 << 128) - 1) else 1


def account_record(account_id, flags=0):
    return tb.Account(id=account_id, debits_pending=0, debits_posted=0,
        credits_pending=0, credits_posted=0, user_data_128=0, user_data_64=0,
        user_data_32=0, ledger=LEDGER, code=CODE, flags=flags, timestamp=0)


def transfer_record(transfer_id, debit, credit, amount, flags=0, pending_id=0):
    return tb.Transfer(id=transfer_id, debit_account_id=debit,
        credit_account_id=credit, amount=amount, pending_id=pending_id,
        user_data_128=0, user_data_64=0, user_data_32=0, timeout=0,
        ledger=LEDGER, code=CODE, flags=flags, timestamp=0)


class UncertainLedgerError(RuntimeError):
    """A ledger write could not be confirmed; do not dispatch to the rail."""


class Ledger:
    def __init__(self, address):
        self.client = tb.ClientSync(cluster_id=0, replica_addresses=address)

    def close(self):
        self.client.close()

    @staticmethod
    def ids(account_id):
        return (identity(account_id + ':payer'), identity(account_id + ':sink'),
                identity(account_id + ':funding'))

    @staticmethod
    def payment_ids(payment_id):
        return (identity(payment_id + ':pending'), identity(payment_id + ':post'),
                identity(payment_id + ':void'))

    def _account(self, id_value):
        rows = self.client.lookup_accounts([id_value])
        return rows[0] if rows else None

    def _transfer(self, id_value):
        rows = self.client.lookup_transfers([id_value])
        return rows[0] if rows else None

    def _create_account(self, record):
        results = self.client.create_accounts([record])
        if not results or results[0].status == tb.CreateAccountStatus.CREATED:
            return
        if results[0].status == tb.CreateAccountStatus.EXISTS:
            existing = self._account(record.id)
            if existing and existing.ledger == record.ledger and existing.code == record.code and existing.flags == record.flags:
                return
        raise UncertainLedgerError('account creation not confirmed')

    def _matches(self, existing, requested):
        return existing is not None and all(getattr(existing, field) == getattr(requested, field)
            for field in ('id', 'debit_account_id', 'credit_account_id', 'amount',
                          'pending_id', 'ledger', 'code', 'flags'))

    def _create_transfer(self, record):
        try:
            results = self.client.create_transfers([record])
        except Exception as error:
            try:
                if self._matches(self._transfer(record.id), record):
                    return 'confirmed'
            except Exception:
                pass
            raise UncertainLedgerError('transfer outcome not confirmed') from error
        if not results or results[0].status == tb.CreateTransferStatus.CREATED:
            return 'created'
        if results[0].status == tb.CreateTransferStatus.EXCEEDS_CREDITS:
            return 'insufficient'
        if results[0].status == tb.CreateTransferStatus.EXISTS and self._matches(
                self._transfer(record.id), record):
            return 'confirmed'
        raise UncertainLedgerError('transfer result not confirmed')

    def seed(self, account_id, amount):
        payer, sink, funding = self.ids(account_id)
        self._create_account(account_record(payer,
            tb.AccountFlags.DEBITS_MUST_NOT_EXCEED_CREDITS))
        self._create_account(account_record(sink))
        self._create_account(account_record(funding))
        result = self._create_transfer(transfer_record(
            identity(account_id + ':funding-transfer'), funding, payer, amount))
        if result == 'insufficient':
            raise UncertainLedgerError('funding refused')
        return self.account(account_id)

    def account(self, account_id):
        payer = self._account(self.ids(account_id)[0])
        if payer is None:
            raise UncertainLedgerError('payer account unavailable')
        return (payer.credits_posted - payer.debits_posted,
                payer.debits_pending)

    def reserve(self, payment_id, account_id, amount):
        payer, sink, _ = self.ids(account_id)
        pending, _, _ = self.payment_ids(payment_id)
        result = self._create_transfer(transfer_record(
            pending, payer, sink, amount, tb.TransferFlags.PENDING))
        if result == 'insufficient':
            return False
        if not self._matches(self._transfer(pending), transfer_record(
                pending, payer, sink, amount, tb.TransferFlags.PENDING)):
            raise UncertainLedgerError('pending transfer lookup mismatch')
        return True

    def finalize(self, payment_id, account_id, amount, status):
        payer, sink, _ = self.ids(account_id)
        pending, post, void = self.payment_ids(payment_id)
        if status == 'SETTLED':
            record = transfer_record(post, payer, sink, amount,
                tb.TransferFlags.POST_PENDING_TRANSFER, pending)
        elif status == 'REJECTED':
            record = transfer_record(void, payer, sink, amount,
                tb.TransferFlags.VOID_PENDING_TRANSFER, pending)
        else:
            raise ValueError('unsupported synthetic outcome')
        self._create_transfer(record)
        if not self._matches(self._transfer(record.id), record):
            raise UncertainLedgerError('resolution transfer lookup mismatch')

    def effects(self, payment_id, account_id, amount):
        payer, sink, _ = self.ids(account_id)
        pending, post, void = self.payment_ids(payment_id)
        expected_pending = transfer_record(pending, payer, sink, amount,
            tb.TransferFlags.PENDING)
        rows = []
        if self._matches(self._transfer(pending), expected_pending):
            rows.append('HOLD')
        expected_post = transfer_record(post, payer, sink, amount,
            tb.TransferFlags.POST_PENDING_TRANSFER, pending)
        if self._matches(self._transfer(post), expected_post):
            rows.append('POST')
        expected_void = transfer_record(void, payer, sink, amount,
            tb.TransferFlags.VOID_PENDING_TRANSFER, pending)
        if self._matches(self._transfer(void), expected_void):
            rows.append('RELEASE')
        return rows
