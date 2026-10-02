#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :app:assembleModernDebug -PaiDev=true "$@"
mkdir -p build/ai-dev
python3 tools/package-ai-dev-update.py
printf '\nAPK: %s/build/ai-dev/GameNative-AI-Dev.apk\n' "$PWD"
