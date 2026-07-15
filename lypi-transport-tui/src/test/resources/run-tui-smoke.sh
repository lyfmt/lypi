#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

mvn -pl lypi-transport-tui -am test

if rg "cn\\.lypi\\.(agent|session|security|ai\\.provider|tool|resource|boot)\\." lypi-transport-tui/src/main/java; then
  echo "forbidden dependency reference found in lypi-transport-tui" >&2
  exit 1
fi

if ! command -v script >/dev/null 2>&1; then
  echo "script command is required for tui PTY smoke" >&2
  exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

cat > "$TMP_DIR/TuiPtyProbe.java" <<'EOF'
import cn.lypi.transport.tui.TerminalSession;

public final class TuiPtyProbe {
  public static void main(String[] args) throws Exception {
    try (TerminalSession ignored = TerminalSession.open()) {
      System.out.print("LYPI_TUI_PTY_OPEN\n");
    }
    System.out.print("LYPI_TUI_PTY_CLOSED\n");
  }
}
EOF

mvn -q -pl lypi-transport-tui dependency:build-classpath -Dmdep.outputFile="$TMP_DIR/dependency-classpath.txt"
DEPENDENCY_CLASSPATH="$(cat "$TMP_DIR/dependency-classpath.txt")"
PROBE_CLASSPATH="$ROOT/lypi-transport-tui/target/classes:$ROOT/lypi-contracts/target/classes:$DEPENDENCY_CLASSPATH"

javac -cp "$PROBE_CLASSPATH" -d "$TMP_DIR" "$TMP_DIR/TuiPtyProbe.java"

PTY_OUTPUT="$TMP_DIR/pty-output.log"
printf -v PTY_COMMAND 'TERM=xterm-256color java -cp %q TuiPtyProbe' "$TMP_DIR:$PROBE_CLASSPATH"
timeout 15 script -q -e -c "$PTY_COMMAND" "$PTY_OUTPUT" >/dev/null

expected_sequences=(
  $'\033[?1049h'
  $'\033[?1000h'
  $'\033[?1006h'
  $'\033[?2004h'
  $'\033[?25l'
  "LYPI_TUI_PTY_OPEN"
  $'\033[?2004l'
  $'\033[?1006l'
  $'\033[?1000l'
  $'\033[?1049l'
  $'\033[?25h'
  "LYPI_TUI_PTY_CLOSED"
)
previous_offset=-1
for expected in "${expected_sequences[@]}"; do
  match="$(LC_ALL=C grep -aFbo -m1 "$expected" "$PTY_OUTPUT" || true)"
  if [[ -z "$match" ]]; then
    echo "missing expected PTY smoke output: $(printf '%q' "$expected")" >&2
    exit 1
  fi
  offset="${match%%:*}"
  if (( offset <= previous_offset )); then
    echo "PTY mode sequence is out of order at: $(printf '%q' "$expected")" >&2
    exit 1
  fi
  previous_offset="$offset"
done

bash "$ROOT/lypi-transport-tui/src/test/resources/run-tui-frame-pty.sh"
bash "$ROOT/lypi-transport-tui/src/test/resources/run-tui-interaction-pty.sh"

echo "tui smoke passed"
