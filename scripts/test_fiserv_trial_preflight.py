import tempfile
import unittest
from pathlib import Path

import fiserv_trial_preflight as preflight


class PreflightTest(unittest.TestCase):
    def test_rejects_non_cert_destination(self):
        config = {
            "FISERV_TRIAL_BASE_URL": "https://example.com",
            "FISERV_TRIAL_TOKEN_URL": preflight.TOKEN_URL,
            "FISERV_TRIAL_API_KEY": "synthetic-key",
            "FISERV_TRIAL_API_SECRET": "synthetic-secret",
            "FISERV_TRIAL_ORG_ID": "synthetic-org",
        }
        with self.assertRaisesRegex(ValueError, "approved Banking Hub CERT"):
            preflight.validate(config)

    def test_missing_credentials_fail_before_network(self):
        config = {
            "FISERV_TRIAL_BASE_URL": preflight.HOST_URL,
            "FISERV_TRIAL_TOKEN_URL": preflight.TOKEN_URL,
        }
        with self.assertRaisesRegex(ValueError, "incomplete"):
            preflight.validate(config)

    def test_config_parser_preserves_secret_with_equals(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "trial.env"
            path.write_text("# local\nFISERV_TRIAL_API_SECRET=a=b=c\n")
            self.assertEqual(preflight.read_config(path)["FISERV_TRIAL_API_SECRET"], "a=b=c")

    def test_account_read_requires_matching_identity_and_balance_entries(self):
        response = {"AcctRec": {"AcctKeys": {"AcctId": "sample"},
                                "DepositAcctInfo": {"AcctBal": [{"BalType": "example"}]}}}
        self.assertEqual(preflight.assess_account_response(response, "sample"), (True, 1))
        with self.assertRaisesRegex(ValueError, "identity"):
            preflight.assess_account_response(response, "other")
        response["AcctRec"]["DepositAcctInfo"]["AcctBal"] = []
        with self.assertRaisesRegex(ValueError, "balance entries"):
            preflight.assess_account_response(response, "sample")


if __name__ == "__main__":
    unittest.main()
