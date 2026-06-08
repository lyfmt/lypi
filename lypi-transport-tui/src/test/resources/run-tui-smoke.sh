#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

mvn -pl lypi-transport-tui -am test

if rg "cn\\.lypi\\.(agent|session|security|ai\\.provider|tool|resource|boot)\\." lypi-transport-tui/src/main/java; then
  echo "forbidden dependency reference found in lypi-transport-tui" >&2
  exit 1
fi

echo "tui smoke passed"
