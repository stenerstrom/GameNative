#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :app:assembleModernDebug -PaiDev=true "$@"
mkdir -p build/ai-dev
cp app/build/outputs/apk/modern/debug/app-modern-debug.apk build/ai-dev/GameNative-AI-Dev.apk
printf '\nAPK: %s/build/ai-dev/GameNative-AI-Dev.apk\n' "$PWD"
