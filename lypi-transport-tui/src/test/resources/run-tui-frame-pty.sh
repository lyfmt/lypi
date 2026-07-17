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
  'TERM=xterm-256color java -cp %q cn.lypi.transport.tui.TuiFramePtyProbe %q %q; status=$?; printf "\nLYPI_TUI_FRAME_EXIT=%%s\n" "$status"; sleep 30' \
  "$PROBE_CLASSPATH" "$READY_FILE" "$EXIT_FILE"
tmux -L "$TMUX_SOCKET" new-session -d -x 60 -y 12 "$PTY_COMMAND"

FULL_CAPTURE="$TMP_DIR/full-pane.txt"
probe_ready=false
for _ in $(seq 1 100); do
  if [[ -f "$READY_FILE" ]]; then
    probe_ready=true
    break
  fi
  sleep 0.05
done

tmux -L "$TMUX_SOCKET" capture-pane -p -S - >"$FULL_CAPTURE"
if [[ "$probe_ready" != true ]]; then
  echo "tui frame PTY probe did not become ready" >&2
  cat "$FULL_CAPTURE" >&2
  exit 1
fi

expected_lines=(
  'SHELL_SENTINEL'
  'history stable'
  'stream/live row'
  '> input|'
  'status-updated'
)
for expected in "${expected_lines[@]}"; do
  mapfile -t matches < <(grep -nxF "$expected" "$FULL_CAPTURE" || true)
  if [[ "${#matches[@]}" -ne 1 ]]; then
    echo "expected exactly one full-capture line: $expected" >&2
    cat "$FULL_CAPTURE" >&2
    exit 1
  fi
done
if [[ "$(grep -Fc 'LY-PI' "$FULL_CAPTURE" || true)" -ne 1 ]]; then
  echo "expected startup banner exactly once" >&2
  cat "$FULL_CAPTURE" >&2
  exit 1
fi
if grep -Fq "status-old" "$FULL_CAPTURE"; then
  echo "stale status entered terminal scrollback" >&2
  cat "$FULL_CAPTURE" >&2
  exit 1
fi

: >"$EXIT_FILE"
POST_CLOSE_CAPTURE="$TMP_DIR/post-close-pane.txt"
probe_finished=false
for _ in $(seq 1 100); do
  tmux -L "$TMUX_SOCKET" capture-pane -p -S - >"$POST_CLOSE_CAPTURE"
  if grep -Fq "LYPI_TUI_FRAME_EXIT=" "$POST_CLOSE_CAPTURE"; then
    probe_finished=true
    break
  fi
  sleep 0.05
done

if [[ "$probe_finished" != true ]] || ! grep -Fxq "LYPI_TUI_FRAME_EXIT=0" "$POST_CLOSE_CAPTURE"; then
  echo "tui frame PTY probe did not close cleanly" >&2
  cat "$POST_CLOSE_CAPTURE" >&2
  exit 1
fi
for retained in 'SHELL_SENTINEL' 'history stable'; do
  if [[ "$(grep -Fxc "$retained" "$POST_CLOSE_CAPTURE" || true)" -ne 1 ]]; then
    echo "expected retained scrollback line exactly once after close: $retained" >&2
    cat "$POST_CLOSE_CAPTURE" >&2
    exit 1
  fi
done
if [[ "$(grep -Fc 'LY-PI' "$POST_CLOSE_CAPTURE" || true)" -ne 1 ]]; then
  echo "expected startup banner retained exactly once after close" >&2
  cat "$POST_CLOSE_CAPTURE" >&2
  exit 1
fi
for mutable in 'stream/live row' '> input|' 'status-old' 'status-updated'; do
  if grep -Fxq "$mutable" "$POST_CLOSE_CAPTURE"; then
    echo "mutable surface remained after close: $mutable" >&2
    cat "$POST_CLOSE_CAPTURE" >&2
    exit 1
  fi
done

echo "tui frame PTY passed"
