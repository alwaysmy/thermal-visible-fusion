#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
find storage-core/src/main/java storage-core/src/test/java -name '*.java' -print0 |
  xargs -0 javac --release 17 -d "$out"
java -cp "$out" org.thermalfusion.storage.StorageCoreTest
python3 scripts/check-storage-policy.py
