#!/usr/bin/env python3
"""Upload an external signing configuration to GitHub without printing secrets."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="astra-sec/astra-tv")
    parser.add_argument("--properties", type=Path,
                        default=Path.home() / ".android/astra-sec/signing.properties")
    args = parser.parse_args()
    values = {}
    for line in args.properties.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")):
            name, separator, value = line.partition("=")
            if not separator:
                raise SystemExit("Invalid signing properties file.")
            values[name.strip()] = value.strip()
    names = ["ASTRA_SIGNING_STORE_FILE", "ASTRA_SIGNING_STORE_PASSWORD",
             "ASTRA_SIGNING_KEY_ALIAS", "ASTRA_SIGNING_KEY_PASSWORD"]
    if any(not values.get(name) for name in names):
        raise SystemExit("All four ASTRA_SIGNING properties are required.")
    keystore = Path(values["ASTRA_SIGNING_STORE_FILE"]).expanduser()
    if not keystore.is_absolute():
        keystore = args.properties.parent / keystore
    java_home = os.environ.get("JAVA_HOME")
    keytool = str(Path(java_home) / "bin/keytool") if java_home else shutil.which("keytool")
    if not keytool or not shutil.which("gh"):
        raise SystemExit("JDK keytool and an authenticated GitHub CLI are required.")
    environment = os.environ.copy()
    environment["ASTRA_SECRET_UPLOAD_PASSWORD"] = values["ASTRA_SIGNING_STORE_PASSWORD"]
    certificate = subprocess.run([
        keytool, "-exportcert", "-rfc", "-keystore", str(keystore),
        "-alias", values["ASTRA_SIGNING_KEY_ALIAS"],
        "-storepass:env", "ASTRA_SECRET_UPLOAD_PASSWORD",
    ], env=environment, capture_output=True, text=True)
    if certificate.returncode:
        raise SystemExit("Could not read the public signing certificate.")
    encoded = "".join(line for line in certificate.stdout.splitlines()
                      if not line.startswith("-----"))
    fingerprint = hashlib.sha256(base64.b64decode(encoded)).hexdigest()
    expected = json.loads((Path(__file__).resolve().parents[1] /
                           "docs/signing/astra-sec-release.json").read_text())
    if fingerprint != expected["certificateSha256"]:
        raise SystemExit("Keystore does not match the published Astra Sec certificate.")
    secrets = {name: values[name] for name in names[1:]}
    secrets["ASTRA_SIGNING_KEYSTORE_BASE64"] = base64.b64encode(keystore.read_bytes()).decode()
    for name, value in secrets.items():
        result = subprocess.run(["gh", "secret", "set", name, "--repo", args.repo],
                                input=value, capture_output=True, text=True)
        if result.returncode:
            raise SystemExit(f"Could not update {name}; check GitHub CLI access.")
        print(f"Configured {name} for {args.repo}.")


if __name__ == "__main__":
    main()
