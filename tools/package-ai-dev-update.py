#!/usr/bin/env python3
"""Package an already-built AI Dev APK and fail on an accidental identity/key change."""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

root = Path(__file__).resolve().parent.parent
def properties(path):
    return dict(line.strip().split("=", 1) for line in path.read_text().splitlines() if "=" in line and not line.lstrip().startswith("#"))

config = properties(root / "ai-dev.properties")
sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or properties(root / "local.properties")["sdk.dir"])
metadata = json.loads((root / "app/build/outputs/apk/modern/debug/output-metadata.json").read_text())
assert metadata["applicationId"] == "app.gamenative.aidev", "Wrong package; refusing to package an upstream APK"
assert len(metadata["elements"]) == 1, "Expected a single installable APK"
entry = metadata["elements"][0]
code, version = entry["versionCode"], entry["versionName"]
assert code == int(config["versionCode"]) and code > 23, "Increase AI Dev versionCode above prior installations"
assert version.endswith("-" + config["versionSuffix"]), "Wrong AI Dev version name"
apk = root / "app/build/outputs/apk/modern/debug" / entry["outputFile"]
signer = sdk / "build-tools/35.0.0/apksigner"
verified = subprocess.run([str(signer), "verify", "--print-certs", str(apk)], check=True, capture_output=True, text=True)
certs = re.findall(r"certificate SHA-256 digest: ([a-f0-9]+)", verified.stdout)
assert certs == [config["signerSha256"]], "Signing certificate changed; do not distribute or uninstall the existing app"
with apk.open("rb") as source:
    digest = hashlib.file_digest(source, "sha256").hexdigest() if hasattr(hashlib, "file_digest") else hashlib.sha256(source.read()).hexdigest()
out = root / "build/ai-dev"
out.mkdir(parents=True, exist_ok=True)
filename = f"GameNative-AI-Dev-{version}.apk"
shutil.copy2(apk, out / filename)
shutil.copy2(apk, out / "GameNative-AI-Dev.apk")
notes = (root / "docs/ai-dev-release-notes.txt").read_text().strip()
manifest = dict(schemaVersion=1, packageName=metadata["applicationId"], versionCode=code, versionName=version,
                apkUrl=f"https://github.com/stenerstrom/GameNative/releases/download/ai-dev-{code}/{filename}",
                size=apk.stat().st_size, sha256=digest, notes=notes)
(out / "ai-dev-update.json").write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n")
(out / "SHA256SUMS").write_text(f"{digest}  {filename}\n{digest}  GameNative-AI-Dev.apk\n")
print(f"Verified update: {filename}, versionCode={code}; manifest: {out / 'ai-dev-update.json'}")
