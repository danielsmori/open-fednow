#!/usr/bin/env python3
"""Opt-in Banking Hub Trial authentication check; never prints credentials or tokens."""

import argparse
import base64
import json
from pathlib import Path
import sys
from urllib.error import HTTPError, URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


HOST_URL = "https://bankinghub-cert.fiservapis.com/banking/efx/v1"
TOKEN_URL = "https://bankinghub-cert.fiservapis.com/fts-apim/oauth2/v2"
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


def authenticate(config: dict[str, str]) -> tuple[str, int]:
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
    return token_type, int(data.get("expires_in", 0))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=Path.home() / ".config/openfednow/fiserv-trial.env")
    parser.add_argument("--authenticate", action="store_true", help="Send one token request to Banking Hub CERT")
    args = parser.parse_args()
    try:
        config = read_config(args.config)
        validate(config)
        if not args.authenticate:
            print("trial_config=ready provider_auth=not_run")
            return 0
        token_type, expires_in = authenticate(config)
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
