import copy
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
from s02_ledger_rail_comparator import EXPECTED, evaluate, status_only
from compare_s02 import normalize, portable_decision, scoring_valid, summarize, trial_records


class S02ComparisonTest(unittest.TestCase):
    def test_independent_checklist_detects_both_faults_and_no_clean_alarm(self):
        clean = dict(EXPECTED)
        self.assertEqual(evaluate(clean)["result"], "PASS")
        self.assertEqual(status_only(clean)["result"], "PASS")
        replay = dict(clean, submission_attempts=2, duplicate_submission_events=1)
        self.assertEqual(evaluate(replay)["failed_assertions"],
                         ["submission_attempts", "duplicate_submission_events"])
        self.assertEqual(status_only(replay)["result"], "PASS")
        missing_post = dict(clean, post_count=0, post_amount_minor=0)
        self.assertEqual(evaluate(missing_post)["result"], "DETECT")
        self.assertEqual(status_only(missing_post)["result"], "PASS")

    def test_missing_rail_is_unavailable_not_a_detected_defect(self):
        missing = dict(EXPECTED, rail_status=None, submission_attempts=None,
                       settlement_events=None, duplicate_submission_events=None)
        result = evaluate(missing)
        self.assertEqual(result["result"], "UNAVAILABLE")
        self.assertEqual(result["failed_assertions"], [])
        self.assertEqual(len(result["unavailable_assertions"]), 4)

    def test_portable_wrapper_reuses_shared_effect_oracle(self):
        trace = {"recovery": {"before_inquiry": {"state": "OUTCOME_UNKNOWN"},
            "account_before_inquiry": {"ledgerMinor": 100000, "heldMinor": 10000},
            "duplicate_operation_id": "SYNTHETIC-OP"}}
        snapshot = {"local": {"state": "SETTLED", "operationId": "SYNTHETIC-OP"},
            "account": {"ledgerMinor": 90000, "heldMinor": 0},
            "effects": [{"effectType": "HOLD", "amountMinor": 10000},
                        {"effectType": "POST", "amountMinor": 10000}],
            "remote": {"status": "SETTLED", "submitCount": 1}}
        self.assertEqual(portable_decision(trace, snapshot)["result"], "PASS")
        replay = copy.deepcopy(snapshot)
        replay["remote"]["submitCount"] = 2
        self.assertEqual(portable_decision(trace, replay)["reason"],
                         "duplicate_remote_submission")
        unavailable = copy.deepcopy(snapshot)
        unavailable["remote"] = None
        self.assertEqual(portable_decision(trace, unavailable)["result"], "UNAVAILABLE")
        observed = normalize(trace, snapshot, [{"kind": "REMOTE_EFFECT", "status": "SETTLED"}])
        self.assertEqual(observed["post_count"], 1)

    def test_clean_comparator_false_alarm_is_recorded(self):
        local = {"state": "SETTLED", "operationId": "SYNTHETIC-OP"}
        account = {"ledgerMinor": 90000, "heldMinor": 0}
        effects = [{"effectType": "HOLD", "amountMinor": 10000},
                   {"effectType": "POST", "amountMinor": 10000}]
        trace = {"recovery": {"before_inquiry": {"state": "OUTCOME_UNKNOWN"},
                 "account_before_inquiry": {"ledgerMinor": 100000, "heldMinor": 10000},
                 "duplicate_operation_id": "SYNTHETIC-OP"},
                 "local": local, "account": account, "effects": effects,
                 "remote": {"status": "SETTLED", "submitCount": 1}}
        replay = {"local": local, "account": account, "effects": effects,
                  "remote": {"status": "SETTLED", "submitCount": 2}}
        clean_events = [{"kind": "REMOTE_EFFECT", "status": "SETTLED"}]
        replay_events = clean_events + [{"kind": "DUPLICATE_SUBMIT"}]
        driver = SimpleNamespace(last_payment={"messageId": "SYNTHETIC-MESSAGE"},
                                 lookup=lambda _: (200, local))
        rail = Mock()
        rail.call.return_value = (200, {})
        original_evaluate = evaluate

        def false_alarm_on_clean(observed):
            if observed["submission_attempts"] == 1 and observed["post_count"] == 1:
                return {"result": "DETECT", "failed_assertions": ["forced_false_alarm"],
                        "unavailable_assertions": []}
            return original_evaluate(observed)

        with patch("compare_s02.run_case", return_value=trace), \
             patch("compare_s02.events_for", side_effect=[clean_events, replay_events]), \
             patch("compare_s02.final_snapshot", return_value=replay), \
             patch("compare_s02.evaluate", side_effect=false_alarm_on_clean):
            records = trial_records("python_sqlite", 1, driver, rail)

        self.assertEqual(len(records), 4)
        self.assertEqual(records[0]["workflows"]["ledger_rail_comparator"]["result"], "DETECT")
        self.assertEqual(summarize(records)["ledger_rail_comparator"]["clean_false_alarms"], 1)
        self.assertFalse(scoring_valid(records))


if __name__ == "__main__":
    unittest.main()
