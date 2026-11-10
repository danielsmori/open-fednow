"""Independent, predeclared S02 ledger-and-rail reconciliation checklist.

This reference comparator deliberately does not import OpenFedNow's scenario
runner or oracle. It consumes the same normalized observations as the wrapper.
"""

EXPECTED = {
    "pending_state": "OUTCOME_UNKNOWN",
    "pending_booked_minor": 100000,
    "pending_held_minor": 10000,
    "same_operation_on_repeat": True,
    "final_state": "SETTLED",
    "final_booked_minor": 90000,
    "final_held_minor": 0,
    "hold_count": 1,
    "hold_amount_minor": 10000,
    "post_count": 1,
    "post_amount_minor": 10000,
    "release_count": 0,
    "rail_status": "SETTLED",
    "submission_attempts": 1,
    "settlement_events": 1,
    "duplicate_submission_events": 0,
}


def evaluate(observed):
    """Return PASS, DETECT, or UNAVAILABLE with per-assertion coverage."""
    failed, unavailable = [], []
    for assertion, expected in EXPECTED.items():
        value = observed.get(assertion)
        if value is None:
            unavailable.append(assertion)
        elif value != expected:
            failed.append(assertion)
    result = "DETECT" if failed else "UNAVAILABLE" if unavailable else "PASS"
    return {"result": result, "failed_assertions": failed,
            "unavailable_assertions": unavailable}


def status_only(observed):
    value = observed.get("final_state")
    if value is None:
        return {"result": "UNAVAILABLE", "reason": "final_state_unavailable"}
    return {"result": "PASS" if value == "SETTLED" else "DETECT"}
