#!/usr/bin/env python3
"""Re-authenticate the Home Assistant Rivian integration by minting a fresh Rivian
token set and writing it back into HA's config entry.

Background: logging into the same Rivian account elsewhere (e.g. the wearvian
companion app) rotates the session and invalidates the access/refresh/session
tokens HA had stored, so HA can no longer auth. This does a clean login and patches
the three token fields in HA's `.storage/core.config_entries` (or a single exported
entry).

Needs internet (it talks to the Rivian gateway). If your HA box is offline, run this
against a COPY of the file on a networked machine, then copy the patched file back.

IMPORTANT: stop Home Assistant (or at least the Rivian integration) before patching
the live file — HA holds config entries in memory and will overwrite your edit on
shutdown. Restart HA afterwards.

  python3 ha_reauth.py [path-to-core.config_entries]   # default: ./core.config_entries
"""
import argparse
import getpass
import json
import shutil
import sys
import uuid
import urllib.request
import http.cookiejar

GATEWAY = "https://rivian.com/api/gql/gateway/graphql"
BASE = {
    "User-Agent": "RivianApp/707 CFNetwork/1237 Darwin/20.4.0",
    "Accept": "application/json",
    "Content-Type": "application/json",
    "Apollographql-Client-Name": "com.rivian.ios.consumer-apollo-ios",
}
CSRF_Q = ("mutation CreateCSRFToken {\n  createCsrfToken {\n    __typename\n"
          "    csrfToken\n    appSessionToken\n  }\n}")
LOGIN_Q = ("mutation Login($email: String!, $password: String!) {\n  login(email: $email, "
           "password: $password) {\n    __typename\n    ... on MobileLoginResponse {\n"
           "      __typename\n      accessToken\n      refreshToken\n      userSessionToken\n    }\n"
           "    ... on MobileMFALoginResponse {\n      __typename\n      otpToken\n    }\n  }\n}")
OTP_Q = ("mutation LoginWithOTP($email: String!, $otpCode: String!, $otpToken: String!) {\n"
         "  loginWithOTP(email: $email, otpCode: $otpCode, otpToken: $otpToken) {\n"
         "    __typename\n    ... on MobileLoginResponse {\n      __typename\n"
         "      accessToken\n      refreshToken\n      userSessionToken\n    }\n  }\n}")

_opener = urllib.request.build_opener(
    urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def gql(op, headers, query, variables):
    body = json.dumps({"operationName": op, "query": query, "variables": variables}).encode()
    h = dict(BASE); h["dc-cid"] = f"m-ios-{uuid.uuid4()}"; h.update(headers)
    req = urllib.request.Request(GATEWAY, data=body, headers=h, method="POST")
    with _opener.open(req, timeout=30) as r:
        doc = json.loads(r.read().decode())
    if doc.get("errors"):
        sys.exit(f"{op} failed: {json.dumps(doc['errors'])[:300]}")
    return doc["data"]


def find_rivian_entry(doc):
    entries = doc.get("data", {}).get("entries") if isinstance(doc.get("data"), dict) else None
    if isinstance(entries, list):
        riv = [e for e in entries if e.get("domain") == "rivian"]
        if len(riv) != 1:
            sys.exit(f"expected exactly one rivian entry, found {len(riv)}")
        return riv[0]
    if isinstance(doc.get("data"), dict) and "user_session_token" in doc["data"]:
        return doc
    sys.exit("could not locate a Rivian config entry")


def login(email, password):
    csrf = gql("CreateCSRFToken", {}, CSRF_Q, None)["createCsrfToken"]
    auth_headers = {"Csrf-Token": csrf["csrfToken"], "A-Sess": csrf["appSessionToken"]}
    res = gql("Login", auth_headers, LOGIN_Q, {"email": email, "password": password})["login"]
    if res.get("otpToken"):
        code = input("MFA code (sent to your email/phone): ").strip()
        res = gql("LoginWithOTP", auth_headers, OTP_Q,
                  {"email": email, "otpCode": code, "otpToken": res["otpToken"]})["loginWithOTP"]
    for k in ("accessToken", "refreshToken", "userSessionToken"):
        if not res.get(k):
            sys.exit(f"login response missing {k}: {json.dumps(res)[:200]}")
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path", nargs="?", default="core.config_entries")
    args = ap.parse_args()

    doc = json.load(open(args.path))
    entry = find_rivian_entry(doc)
    email = entry.get("data", {}).get("username") or input("Rivian email: ").strip()
    print(f"Re-authenticating Rivian account: {email}")
    password = getpass.getpass("Rivian password: ")

    tokens = login(email, password)

    entry.setdefault("data", {})
    entry["data"]["access_token"] = tokens["accessToken"]
    entry["data"]["refresh_token"] = tokens["refreshToken"]
    entry["data"]["user_session_token"] = tokens["userSessionToken"]

    backup = args.path + ".bak"
    shutil.copy2(args.path, backup)
    with open(args.path, "w") as f:
        json.dump(doc, f)
    print(f"\nPatched {args.path} (backup at {backup}).")
    print("Now restart Home Assistant (or reload the Rivian integration) so it reads the new tokens.")


if __name__ == "__main__":
    main()
