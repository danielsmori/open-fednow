#!/usr/bin/env python3
"""Opt-in Banking Hub Trial authentication check; never prints credentials or tokens."""

import argparse
import base64
import json
from pathlib import Path
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


HOST_URL = "https://bankinghub-cert.fiservapis.com/banking/efx/v1"
TOKEN_URL = "https://bankinghub-cert.fiservapis.com/fts-apim/oauth2/v2"
ACCOUNT_DETAILS_PATH = "/acctservice/acctmgmt/accounts/secured"
REQUIRED = (
    "FISERV_TRIAL_BASE_URL",
    "FISERV_TRIAL_TOKEN_URL",
    "FISERV_TRIAL_API_KEY",
    "FISERV_TRIAL_API_SECRET",
    "FISERV_TRIAL_ORG_ID",
)


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        return None


def read_config(path: Path) -> dict[str, str]:
    values = {}
    for line in path.read_text().splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if "=" not in line:
            raise ValueError("Malformed config line")
        key, value = line.split("=", 1)
        key = key.strip()
        if key in values:
            raise ValueError("Duplicate config field")
        values[key] = value.strip()
    return values


def validate(config: dict[str, str]) -> None:
    if any(not config.get(key) for key in REQUIRED):
        raise ValueError("Required Trial configuration is incomplete")
    if config["FISERV_TRIAL_BASE_URL"] != HOST_URL or config["FISERV_TRIAL_TOKEN_URL"] != TOKEN_URL:
        raise ValueError("Trial host or token URL does not match the approved Banking Hub CERT endpoint")
    if ":" in config["FISERV_TRIAL_API_KEY"]:
        raise ValueError("API key cannot contain a colon in Basic authentication")


def fetch_token(config: dict[str, str]) -> tuple[str, str, int]:
    credentials = f'{config["FISERV_TRIAL_API_KEY"]}:{config["FISERV_TRIAL_API_SECRET"]}'
    basic = base64.b64encode(credentials.encode("utf-8")).decode("ascii")
    request = Request(
        TOKEN_URL,
        data=b"grant_type=client_credentials",
        headers={
            "Authorization": f"Basic {basic}",
            "Content-Type": "application/x-www-form-urlencoded",
            "Accept": "application/json",
        },
        method="POST",
    )
    with build_opener(NoRedirect).open(request, timeout=15) as response:
        data = json.load(response)
    if not isinstance(data, dict) or not isinstance(data.get("access_token"), str) or not data["access_token"]:
        raise ValueError("Token response lacks an access token")
    token_type = data.get("token_type")
    if token_type != "Bearer":
        raise ValueError("Unexpected token type")
    return data["access_token"], token_type, int(data.get("expires_in", 0))


def read_sample_dda(config: dict[str, str]) -> tuple[bool, int]:
    account_id = config.get("FISERV_TRIAL_ACCOUNT_ID")
    if not account_id or config.get("FISERV_TRIAL_ACCOUNT_TYPE") != "DDA":
        raise ValueError("Approved Premier DDA fixture is not configured")
    token, _, _ = fetch_token(config)
    body = json.dumps({"AcctSel": {"AcctKeys": {"AcctId": account_id, "AcctType": "DDA"}}}).encode()
    efx_header = json.dumps({"OrganizationId": config["FISERV_TRIAL_ORG_ID"], "TrnId": str(uuid.uuid4())})
    request = Request(
        HOST_URL + ACCOUNT_DETAILS_PATH,
        data=body,
        headers={
            "Authorization": f"Bearer {token}",
            "EFXHeader": efx_header,
            "Content-Type": "application/json",
            "Accept": "application/json",
        },
        method="POST",
    )
    with build_opener(NoRedirect).open(request, timeout=15) as response:
        data = json.load(response)
    return assess_account_response(data, account_id)


def assess_account_response(data: object, account_id: str) -> tuple[bool, int]:
    record = data.get("AcctRec") if isinstance(data, dict) else None
    if not isinstance(record, dict):
        raise ValueError("Account response lacks an account record")
    keys = record.get("AcctKeys")
    details = record.get("DepositAcctInfo")
    balances = details.get("AcctBal") if isinstance(details, dict) else None
    if not isinstance(keys, dict) or keys.get("AcctId") != account_id:
        raise ValueError("Account response identity does not match the request")
    if not isinstance(balances, list) or not balances:
        raise ValueError("Account response lacks balance entries")
    return True, len(balances)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path.home() / ".config/openfednow/fiserv-trial.env")
    actions = parser.add_mutually_exclusive_group()
    actions.add_argument("--authenticate", action="store_true", help="Send one token request to Banking Hub CERT")
    actions.add_argument("--read-sample-dda", action="store_true", help="Read Fiserv's published Premier Trial DDA fixture")
    args = parser.parse_args()
    try:
        config = read_config(args.config)
        validate(config)
        if not args.authenticate and not args.read_sample_dda:
            print("trial_config=ready provider_auth=not_run")
            return 0
        if args.read_sample_dda:
            matches, count = read_sample_dda(config)
            print(f"sample_dda_read={'pass' if matches else 'unverified'} account_id_matches={str(matches).lower()} balance_entries={count}")
            return 0 if matches else 1
        _, token_type, expires_in = fetch_token(config)
        print(f"provider_auth=pass token_type={token_type} expires_in_ms={expires_in}")
        return 0
    except HTTPError as error:
        print(f"provider_auth=failed http_status={error.code}", file=sys.stderr)
    except URLError:
        print("provider_auth=failed network_error", file=sys.stderr)
    except (OSError, ValueError, TypeError, KeyError) as error:
        print(f"provider_auth=not_verified reason={type(error).__name__}", file=sys.stderr)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
