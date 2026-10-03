#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
find storage-core/src/main/java/org/thermalfusion/preview \
     storage-core/src/test/java/org/thermalfusion/preview -name '*.java' -print0 |
  xargs -0 javac --release 17 -Xlint:all -Werror -d "$out"
java -cp "$out" org.thermalfusion.preview.PreviewCoreTest
