#!/usr/bin/env python3
"""Build and verify a signed APK. Credentials are supplied through the environment."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def run(*command):
    return subprocess.check_output(command, cwd=ROOT, text=True)


def main():
    required = ["CODEX_WIDGET_KEYSTORE", "CODEX_WIDGET_STORE_PASSWORD", "CODEX_WIDGET_KEY_ALIAS", "CODEX_WIDGET_KEY_PASSWORD"]
    for name in required:
        if not os.environ.get(name):
            raise SystemExit(f"Missing environment variable: {name}")
    expected_signer = os.environ.get("CODEX_WIDGET_EXPECTED_SIGNER", "ff7f5e3369f7063ad452036834317fda47c1f40d329816f1d3e948004fb0c53c")
    sdk = Path(os.environ["ANDROID_HOME"])
    build_tools = sdk / "build-tools" / "35.0.0"
    subprocess.run(["./gradlew", ":app:testDebugUnitTest", ":app:lintRelease", ":app:assembleRelease"], cwd=ROOT, check=True)
    apk = ROOT / "app/build/outputs/apk/release/app-release.apk"
    cert = run(str(build_tools / "apksigner"), "verify", "--print-certs", str(apk))
    signer = re.search(r"certificate SHA-256 digest: ([0-9a-f]+)", cert).group(1)
    if signer != expected_signer.lower().replace(":", ""):
        raise SystemExit("Unexpected signing certificate. Refusing to package this APK.")
    badging = run(str(build_tools / "aapt"), "dump", "badging", str(apk))
    package = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    app_id, code, version = package.groups()
    if app_id != "top.logge.codexquota" or "application-debuggable" in badging:
        raise SystemExit("Wrong application ID or a debuggable release")
    out = ROOT / "artifacts" / f"v{version}"
    out.mkdir(parents=True, exist_ok=True)
    target = out / f"codex-quota-widget-{version}.apk"
    shutil.copy2(apk, target)
    digest = hashlib.sha256(target.read_bytes()).hexdigest()
    (out / "SHA256SUMS.txt").write_text(f"{digest}  {target.name}\n")
    (out / "build-info.json").write_text(json.dumps({
        "version": version, "versionCode": int(code), "applicationId": app_id,
        "sourceCommit": run("git", "rev-parse", "HEAD").strip(),
        "sourceDirty": bool(run("git", "status", "--porcelain").strip()),
        "apkSha256": digest, "signerSha256": signer, "debuggable": False,
    }, indent=2) + "\n")
    print(f"Verified APK: {target}\nSHA-256: {digest}")


if __name__ == "__main__":
    main()
