#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew :app:assembleRelease :app:testDebugUnitTest :app:lintRelease --console=plain
