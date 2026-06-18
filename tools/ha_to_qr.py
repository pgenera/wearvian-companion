#!/usr/bin/env python3
"""Extract the wearvian key-import bits from a Home Assistant Rivian config entry
and render them as a QR code on the console, for scanning by the companion app.

Offline only — reads the HA file, decodes the embedded keypair/token/ids, and
draws a QR. Makes NO network calls. The companion app does the cloud getUserInfo
resolution (vehiclePublicKey, vasPhoneId, etc.) on the phone, using the
user_session_token carried here.

Input: either a full HA `.storage/core.config_entries` (an object with
data.entries[]) or a single exported entry (the object with options.private_key).
Pass the path; defaults to ./ha-key.json.

  python3 companion/tools/ha_to_qr.py [path] [--json]

The QR payload is compact JSON; --json also prints it for inspection.
"""
import argparse
import base64
import json
import sys

SCHEMA = "wearvian-ha-import"
VERSION = 1


def _b64url_json(segment: str) -> dict:
    """Decode a JWT segment (base64url, no padding) to a dict."""
    pad = "=" * (-len(segment) % 4)
    return json.loads(base64.urlsafe_b64decode(segment + pad))


def find_rivian_entry(doc: dict) -> dict:
    """Return the Rivian config entry from either a full core.config_entries or a
    single exported entry."""
    # Full core.config_entries: {"data": {"entries": [ {entry}, ... ]}}
    entries = doc.get("data", {}).get("entries") if isinstance(doc.get("data"), dict) else None
    if isinstance(entries, list):
        riv = [e for e in entries if e.get("domain") == "rivian"]
        if not riv:
            sys.exit("No entry with domain 'rivian' found in config_entries.")
        if len(riv) > 1:
            sys.exit(f"{len(riv)} Rivian entries found; export a single entry to disambiguate.")
        return riv[0]
    # Single exported entry: has options.private_key at top level.
    if isinstance(doc.get("options"), dict) and "private_key" in doc["options"]:
        return doc
    sys.exit("Could not locate a Rivian entry (no data.entries[] and no options.private_key).")


def extract(entry: dict) -> dict:
    opts = entry.get("options", {})
    data = entry.get("data", {})
    try:
        private_key = opts["private_key"]
        public_key = opts["public_key"]
        vehicle_control = opts["vehicle_control"]
    except KeyError as e:
        sys.exit(f"Entry is missing required option {e}; is BLE key enrollment complete in HA?")
    if not vehicle_control:
        sys.exit("vehicle_control is empty; no vehicle is associated with this key.")

    user_session_token = data.get("user_session_token")
    if not user_session_token:
        sys.exit("No user_session_token in the entry; the companion needs it to resolve the vehicle key.")

    # userId is embedded in the access_token JWT (user_id claim). Best-effort.
    user_id = ""
    access = data.get("access_token", "")
    if access.count(".") == 2:
        try:
            user_id = _b64url_json(access.split(".")[1]).get("user_id", "")
        except Exception:
            pass

    return {
        "v": VERSION,
        "t": SCHEMA,
        "priv": private_key,                 # base64(PEM PKCS#8) P-256 private key
        "pub": public_key,                   # uncompressed EC point hex (the enrolled phone key)
        "veh": vehicle_control[0],           # opaque HA reference (NOT the vehicleId/vasVehicleId);
                                             # a breadcrumb only — the companion resolves the vehicle
                                             # authoritatively via getUserInfo (public key -> enrolled -> vehicle)
        "usess": user_session_token,         # U-Sess for getUserInfo on the phone
        "uid": user_id,                      # Rivian userId (best-effort, from JWT)
        "user": data.get("username", ""),    # for on-phone confirmation
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("path", nargs="?", default="ha-key.json", help="HA config_entries or exported entry JSON")
    ap.add_argument("--json", action="store_true", help="also print the payload JSON")
    args = ap.parse_args()

    with open(args.path) as f:
        doc = json.load(f)
    payload = extract(find_rivian_entry(doc))
    blob = json.dumps(payload, separators=(",", ":"))

    # Summary to stderr so piping the QR/JSON stays clean.
    print(f"Account : {payload['user']}", file=sys.stderr)
    print(f"Vehicle : {payload['veh']}", file=sys.stderr)
    print(f"Pub key : {payload['pub'][:24]}…", file=sys.stderr)
    print(f"Payload : {len(blob)} bytes\n", file=sys.stderr)

    if args.json:
        print(blob)

    import qrcode
    qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, border=2)
    qr.add_data(blob)
    qr.make(fit=True)
    qr.print_ascii(invert=True)
    print(f"\nQR version {qr.version} · scan with the wearvian companion app.", file=sys.stderr)


if __name__ == "__main__":
    main()
