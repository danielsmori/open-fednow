"""Copy this file outside the repository and map a controlled target to the v1 contract.

Each method returns (HTTP-like status, normalized value). Never put credentials,
real account identifiers or raw provider payloads in the portable report. The
scenario runner controls a separate synthetic rail simulator and creates unique
synthetic account/payment IDs; the target must be configured to use that rail.
"""


class ExampleDriver:
    def __init__(self, base_url, username, password, submit_path):
        # Use the target's approved sandbox authentication. Keep secrets local.
        self.base_url = base_url
        self.submit_path = submit_path

    def seed(self, account, amount, exclusive=True, core_mode="SYNC"):
        """Create an isolated synthetic account; return (200, fixture view)."""
        raise NotImplementedError("Map sandbox account setup")

    def submit(self, payment):
        """Submit once; return (200/202, {operationId, state, ...})."""
        raise NotImplementedError("Map payment submission")

    def lookup(self, operation):
        """Read the same operation after retry/inquiry, without creating a new one."""
        raise NotImplementedError("Map operation lookup")

    def effects(self, operation):
        """Return independently observed HOLD/POST/RELEASE effect rows."""
        raise NotImplementedError("Map financial effect observation")

    def account(self, account):
        """Return {ledgerMinor, heldMinor} from the sandbox financial source."""
        raise NotImplementedError("Map account and hold observation")

    def inquiry_due(self, operation):
        """Advance a controlled synthetic inquiry clock; return (200, view)."""
        raise NotImplementedError("Map permitted fault/inquiry control")


def create_driver(base_url, username, password, submit_path):
    return ExampleDriver(base_url, username, password, submit_path)
