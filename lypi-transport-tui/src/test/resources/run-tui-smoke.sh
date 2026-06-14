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

for expected in \
  $'\033[?2004h' \
  $'\033[?25l' \
  "LYPI_TUI_PTY_OPEN" \
  $'\033[?25h' \
  $'\033[?2004l' \
  "LYPI_TUI_PTY_CLOSED"
do
  if ! grep -Fq "$expected" "$PTY_OUTPUT"; then
    echo "missing expected PTY smoke output: $(printf '%q' "$expected")" >&2
    exit 1
  fi
done

echo "tui smoke passed"
