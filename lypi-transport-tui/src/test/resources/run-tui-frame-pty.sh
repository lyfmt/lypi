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
READY_FILE="$TMP_DIR/ready"
EXIT_FILE="$TMP_DIR/exit"

printf -v PTY_COMMAND \
  'printf "SHELL_SENTINEL\n"; TERM=xterm-256color java -cp %q cn.lypi.transport.tui.TuiFramePtyProbe %q %q; status=$?; printf "\nLYPI_TUI_FRAME_EXIT=%%s\n" "$status"; sleep 30' \
  "$PROBE_CLASSPATH" "$READY_FILE" "$EXIT_FILE"
tmux -L "$TMUX_SOCKET" new-session -d -x 60 -y 12 "$PTY_COMMAND"

ALTERNATE_CAPTURE="$TMP_DIR/alternate-pane.txt"
probe_ready=false
for _ in $(seq 1 100); do
  if [[ -f "$READY_FILE" ]]; then
    probe_ready=true
    break
  fi
  sleep 0.05
done

tmux -L "$TMUX_SOCKET" capture-pane -p >"$ALTERNATE_CAPTURE"
if [[ "$probe_ready" != true ]]; then
  echo "tui frame PTY probe did not become ready" >&2
  cat "$ALTERNATE_CAPTURE" >&2
  exit 1
fi
if grep -Fq "SHELL_SENTINEL" "$ALTERNATE_CAPTURE"; then
  echo "primary screen leaked into alternate-screen capture" >&2
  cat "$ALTERNATE_CAPTURE" >&2
  exit 1
fi
if grep -Fq "status-old" "$ALTERNATE_CAPTURE"; then
  echo "stale status-old remained in final pane" >&2
  cat "$ALTERNATE_CAPTURE" >&2
  exit 1
fi
if [[ "$(wc -l <"$ALTERNATE_CAPTURE")" -ne 12 ]]; then
  echo "alternate-screen frame height does not match tmux pane" >&2
  cat "$ALTERNATE_CAPTURE" >&2
  exit 1
fi

expected_lines=(
  'history stable'
  'running $ tool one'
  'tool two'
  '· status-updated'
  '> input|'
  'status'
)
previous_line=0
for expected in "${expected_lines[@]}"; do
  mapfile -t matches < <(grep -nxF "$expected" "$ALTERNATE_CAPTURE" || true)
  if [[ "${#matches[@]}" -ne 1 ]]; then
    echo "expected exactly one final pane line: $expected" >&2
    cat "$ALTERNATE_CAPTURE" >&2
    exit 1
  fi
  current_line="${matches[0]%%:*}"
  if (( current_line <= previous_line )); then
    echo "final pane line order is incorrect at: $expected" >&2
    cat "$ALTERNATE_CAPTURE" >&2
    exit 1
  fi
  previous_line="$current_line"
done

mapfile -t separators < <(grep -nE '^┄+$' "$ALTERNATE_CAPTURE" || true)
if [[ "${#separators[@]}" -ne 1 ]]; then
  echo "expected exactly one history/live separator" >&2
  cat "$ALTERNATE_CAPTURE" >&2
  exit 1
fi
separator_line="${separators[0]%%:*}"
history_line="$(grep -nxF 'history stable' "$ALTERNATE_CAPTURE")"
history_line="${history_line%%:*}"
running_line="$(grep -nxF 'running $ tool one' "$ALTERNATE_CAPTURE")"
running_line="${running_line%%:*}"
if (( history_line >= separator_line || running_line <= separator_line )); then
  echo "history/live regions are ordered incorrectly" >&2
  cat "$ALTERNATE_CAPTURE" >&2
  exit 1
fi
if [[ "$(grep -nxF '> input|' "$ALTERNATE_CAPTURE")" != 10:* ]]; then
  echo "input is not on the fixed bottom content row" >&2
  cat "$ALTERNATE_CAPTURE" >&2
  exit 1
fi
if [[ "$(grep -nxF 'status' "$ALTERNATE_CAPTURE")" != 12:* ]]; then
  echo "status is not on the bottom pane row" >&2
  cat "$ALTERNATE_CAPTURE" >&2
  exit 1
fi

: >"$EXIT_FILE"
PRIMARY_CAPTURE="$TMP_DIR/primary-pane.txt"
probe_finished=false
for _ in $(seq 1 100); do
  tmux -L "$TMUX_SOCKET" capture-pane -p >"$PRIMARY_CAPTURE"
  if grep -Fq "LYPI_TUI_FRAME_EXIT=" "$PRIMARY_CAPTURE"; then
    probe_finished=true
    break
  fi
  sleep 0.05
done

if [[ "$probe_finished" != true ]] || ! grep -Fxq "LYPI_TUI_FRAME_EXIT=0" "$PRIMARY_CAPTURE"; then
  echo "tui frame PTY probe did not close cleanly" >&2
  cat "$PRIMARY_CAPTURE" >&2
  exit 1
fi
if ! grep -Fxq "SHELL_SENTINEL" "$PRIMARY_CAPTURE"; then
  echo "primary screen was not restored" >&2
  cat "$PRIMARY_CAPTURE" >&2
  exit 1
fi
for tui_line in 'history stable' 'running $ tool one' '· status-updated' '> input|'; do
  if grep -Fq "$tui_line" "$PRIMARY_CAPTURE"; then
    echo "TUI content remained after leaving alternate screen: $tui_line" >&2
    cat "$PRIMARY_CAPTURE" >&2
    exit 1
  fi
done

echo "tui frame PTY passed"
