#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

if ! command -v tmux >/dev/null 2>&1; then
  echo "tmux is required for tui frame PTY" >&2
  exit 1
fi

mvn -q -pl lypi-transport-tui -am test-compile

TMP_DIR="$(mktemp -d)"
TMUX_SOCKET="lypi-tui-frame-$$"
cleanup() {
  tmux -L "$TMUX_SOCKET" kill-server >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mvn -q -pl lypi-transport-tui dependency:build-classpath \
  -Dmdep.outputFile="$TMP_DIR/dependency-classpath.txt"
DEPENDENCY_CLASSPATH="$(<"$TMP_DIR/dependency-classpath.txt")"
PROBE_CLASSPATH="$ROOT/lypi-transport-tui/target/test-classes:$ROOT/lypi-transport-tui/target/classes:$ROOT/lypi-contracts/target/classes:$DEPENDENCY_CLASSPATH"

printf -v PTY_COMMAND \
  'TERM=xterm-256color java -cp %q cn.lypi.transport.tui.TuiFramePtyProbe; status=$?; printf "\nLYPI_TUI_FRAME_EXIT=%%s\n" "$status"; sleep 30' \
  "$PROBE_CLASSPATH"
tmux -L "$TMUX_SOCKET" new-session -d -x 60 -y 12 "$PTY_COMMAND"

CAPTURE="$TMP_DIR/pane.txt"
probe_finished=false
for _ in $(seq 1 100); do
  tmux -L "$TMUX_SOCKET" capture-pane -p -S - >"$CAPTURE"
  if grep -Fq "LYPI_TUI_FRAME_EXIT=" "$CAPTURE"; then
    probe_finished=true
    break
  fi
  sleep 0.05
done

if [[ "$probe_finished" != true ]]; then
  echo "tui frame PTY probe did not finish" >&2
  cat "$CAPTURE" >&2
  exit 1
fi
if ! grep -Fxq "LYPI_TUI_FRAME_EXIT=0" "$CAPTURE"; then
  echo "tui frame PTY probe failed" >&2
  cat "$CAPTURE" >&2
  exit 1
fi
if grep -Fq "status-old" "$CAPTURE"; then
  echo "stale status-old remained in final pane" >&2
  cat "$CAPTURE" >&2
  exit 1
fi

expected_lines=(
  'running $ tool one'
  'tool two'
  '· status-updated'
  '> input|'
  'status'
)
previous_line=0
for expected in "${expected_lines[@]}"; do
  mapfile -t matches < <(grep -nxF "$expected" "$CAPTURE" || true)
  if [[ "${#matches[@]}" -ne 1 ]]; then
    echo "expected exactly one final pane line: $expected" >&2
    cat "$CAPTURE" >&2
    exit 1
  fi
  current_line="${matches[0]%%:*}"
  if (( current_line <= previous_line )); then
    echo "final pane line order is incorrect at: $expected" >&2
    cat "$CAPTURE" >&2
    exit 1
  fi
  previous_line="$current_line"
done

echo "tui frame PTY passed"
