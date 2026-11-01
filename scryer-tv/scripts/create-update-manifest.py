#!/usr/bin/env python3
"""Describe a signed APK after verifying package, version and the permanent signer."""
import argparse
import hashlib
import json
import pathlib
import re
import subprocess


def create(apk: pathlib.Path, build_tools: pathlib.Path, root: pathlib.Path) -> dict:
    gradle = (root / "app/build.gradle.kts").read_text()
    version_code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle)[1])
    version_name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle)[1]
    badging = subprocess.check_output([str(build_tools / "aapt"), "dump", "badging", str(apk)], text=True)
    expected = f"package: name='org.scryertv.client' versionCode='{version_code}' versionName='{version_name}' "
    if expected not in badging:
        raise ValueError("APK package/version does not match the source")
    minimum = int(re.search(r"sdkVersion:'(\d+)'", badging)[1])
    output = subprocess.check_output([str(build_tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True, stderr=subprocess.PIPE)
    digests = re.findall(r"^Signer #\d+ certificate SHA-256 digest: ([0-9a-f]+)$", output, re.M)
    certificate = subprocess.check_output(["openssl", "x509", "-in", str(root / "release-signing-certificate.pem"), "-outform", "DER"])
    expected_signer = hashlib.sha256(certificate).hexdigest()
    if digests != [expected_signer]:
        raise ValueError("APK is not signed with the permanent Scryer TV release key")
    return {"applicationId": "org.scryertv.client", "versionCode": version_code, "versionName": version_name,
            "minSdk": minimum, "apkName": apk.name, "size": apk.stat().st_size,
            "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "certificateSha256": expected_signer}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=pathlib.Path, required=True)
    parser.add_argument("--build-tools", type=pathlib.Path, required=True)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    root = pathlib.Path(__file__).resolve().parents[1]
    result = create(args.apk, args.build_tools, root)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(f"Verified release APK: {result['versionName']} ({result['versionCode']}), {result['size']} bytes")


if __name__ == "__main__":
    main()
