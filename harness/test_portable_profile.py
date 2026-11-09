import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import portable_profile as profile
from run import assert_effects


class AllMethods:
    def __getattr__(self, name):
        return lambda *args: None


class PortableProfileTest(unittest.TestCase):
    def setUp(self):
        self.case = next(case for case in profile.load_profile()["cases"] if case["id"] == "S02")

    def test_profile_reuses_stable_scenario_ids(self):
        self.assertEqual([case["id"] for case in profile.load_profile()["cases"]],
                         ["S01", "S02", "S06", "S16"])

    def test_duplicate_rail_submission_is_a_verified_failure(self):
        snapshot = {"effects": [{"effectType": "HOLD"}, {"effectType": "POST"}],
                    "account": {"heldMinor": 0, "ledgerMinor": 90000},
                    "remote": {"status": "SETTLED", "submitCount": 2}}
        with self.assertRaisesRegex(AssertionError, "duplicate remote submission"):
            assert_effects(snapshot, "SETTLED", 10000)
        with patch.object(profile, "run_case", side_effect=AssertionError("duplicate remote submission")):
            result = profile.case_result(self.case, AllMethods(), object())
        self.assertEqual((result["result"], result["reason"]),
                         ("FAIL", "invariant_violation"))

    def test_mismatched_financial_effect_is_a_verified_failure(self):
        snapshot = {"effects": [{"effectType": "HOLD"}, {"effectType": "RELEASE"}],
                    "account": {"heldMinor": 0, "ledgerMinor": 100000},
                    "remote": {"status": "SETTLED", "submitCount": 1}}
        with self.assertRaisesRegex(AssertionError, "financial effects"):
            assert_effects(snapshot, "SETTLED", 10000)

    def test_missing_capability_and_observation_are_blocked(self):
        result = profile.case_result(self.case, object(), object())
        self.assertEqual(result["result"], "BLOCKED")
        self.assertEqual(result["reason"], "driver_capability_unavailable")
        with patch.object(profile, "run_case", return_value={"local": {}}):
            result = profile.case_result(self.case, AllMethods(), object())
        self.assertEqual((result["result"], result["reason"]),
                         ("BLOCKED", "execution_or_observation_unavailable"))
        with patch.object(profile, "run_case", side_effect=RuntimeError("SECRET-PROVIDER-BODY")):
            result = profile.case_result(self.case, AllMethods(), object())
        self.assertNotIn("SECRET-PROVIDER-BODY", json.dumps(result))

    def test_unexpected_final_state_fails_without_leaking_it(self):
        trace = {"local": {"operationId": "OP", "state": "SECRET-STATE"},
                 "account": {"ledgerMinor": 90000, "heldMinor": 0},
                 "effects": [{"effectType": "HOLD"}, {"effectType": "POST"}],
                 "remote": {"status": "SETTLED", "submitCount": 1},
                 "recovery": {"before_inquiry": {"state": "OUTCOME_UNKNOWN"},
                              "account_before_inquiry": {"ledgerMinor": 100000, "heldMinor": 10000},
                              "duplicate_operation_id": "OP"}}
        with patch.object(profile, "run_case", return_value=trace):
            result = profile.case_result(self.case, AllMethods(), object())
        self.assertEqual(result["result"], "FAIL")
        self.assertNotIn("SECRET-STATE", json.dumps(result))

    def test_failure_preserves_allowlisted_partial_observations(self):
        class Target:
            def __getattr__(self, name):
                return lambda *args: (200, {})

            def account(self, account):
                return 200, {"ledgerMinor": 90000, "heldMinor": 0,
                             "accountId": "SENSITIVE-ACCOUNT"}

        class Rail:
            def call(self, method, path, body=None):
                return 200, {"status": "SETTLED", "submitCount": 2,
                             "raw": "SENSITIVE-SECRET"}

        def violating_case(case, driver, rail, mode):
            driver.account("SENSITIVE-ACCOUNT")
            rail.call("GET", "/payments/SENSITIVE-MESSAGE")
            raise AssertionError("duplicate remote submission")

        with patch.object(profile, "run_case", side_effect=violating_case):
            result = profile.case_result(self.case, Target(), Rail())
        self.assertEqual(result["result"], "FAIL")
        self.assertEqual(result["observed"]["rail"]["submission_count"], 2)
        self.assertEqual(result["observed"]["local"]["ledger_minor"], 90000)
        for sensitive in ("SENSITIVE-ACCOUNT", "SENSITIVE-MESSAGE", "SENSITIVE-SECRET"):
            self.assertNotIn(sensitive, json.dumps(result))

    def test_unavailable_target_writes_blocked_report_and_nonzero_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "blocked.json"
            with patch("sys.stdout"):
                code = profile.main(["--target-url", "http://127.0.0.1:1",
                    "--rail-url", "http://127.0.0.1:2", "--target-revision", "unavailable",
                    "--environment-type", "synthetic-local", "--case", "S02",
                    "--output", str(path)])
            report = json.loads(path.read_text())
            self.assertEqual(code, 1)
            self.assertEqual(report["cases"][0]["result"], "BLOCKED")

    def test_report_projection_omits_raw_payloads_and_identifiers(self):
        trace = {"local": {"operationId": "SENSITIVE-OP", "state": "SETTLED",
                           "credential": "SENSITIVE-SECRET"},
                 "account": {"accountId": "SENSITIVE-ACCOUNT", "ledgerMinor": 90000, "heldMinor": 0},
                 "effects": [{"effectType": "HOLD", "raw": "SENSITIVE-SECRET"},
                             {"effectType": "POST"}],
                 "remote": {"status": "SETTLED", "submitCount": 1,
                            "payload": "SENSITIVE-SECRET"},
                 "recovery": {"before_inquiry": {"state": "OUTCOME_UNKNOWN"},
                              "account_before_inquiry": {"ledgerMinor": 100000, "heldMinor": 10000},
                              "duplicate_operation_id": "SENSITIVE-OP"}}
        with patch.object(profile, "run_case", return_value=trace):
            result = profile.case_result(self.case, AllMethods(), object())
        report = profile.build_report(profile.load_profile(), [result], "target-rev",
                                      "example-driver", "synthetic-local", "profile-hash")
        encoded = json.dumps(report)
        self.assertEqual(result["result"], "PASS")
        self.assertTrue(result["observed"]["local"]["repeat_same_operation"])
        for sensitive in ("SENSITIVE-OP", "SENSITIVE-ACCOUNT", "SENSITIVE-SECRET"):
            self.assertNotIn(sensitive, encoded)


if __name__ == "__main__":
    unittest.main()
