#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

if ! command -v tmux >/dev/null 2>&1; then
  echo "tmux is required for TUI interaction PTY" >&2
  exit 1
fi

mvn -q -pl lypi-transport-tui -am test-compile

TMP_DIR="$(mktemp -d)"
TMUX_SOCKET="lypi-tui-interaction-$$"
cleanup() {
  tmux -L "$TMUX_SOCKET" kill-server >/dev/null 2>&1 || true
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

mvn -q -pl lypi-transport-tui dependency:build-classpath \
  -Dmdep.outputFile="$TMP_DIR/dependency-classpath.txt"
DEPENDENCY_CLASSPATH="$(<"$TMP_DIR/dependency-classpath.txt")"
PROBE_CLASSPATH="$ROOT/lypi-transport-tui/target/test-classes:$ROOT/lypi-transport-tui/target/classes:$ROOT/lypi-contracts/target/classes:$DEPENDENCY_CLASSPATH"
CONTROL_DIR="$TMP_DIR/control"
mkdir -p "$CONTROL_DIR"

printf -v PTY_COMMAND \
  'TERM=xterm-256color java -cp %q cn.lypi.transport.tui.TuiInteractionPtyProbe %q; printf "\nLYPI_TUI_INTERACTION_EXIT=%%s\n" "$?"; sleep 20' \
  "$PROBE_CLASSPATH" "$CONTROL_DIR"
tmux -L "$TMUX_SOCKET" new-session -d -x 80 -y 12 "$PTY_COMMAND"

PANE_CAPTURE="$TMP_DIR/pane.txt"
wait_for_file() {
  local path="$1"
  for _ in $(seq 1 200); do
    [[ -f "$path" ]] && return 0
    sleep 0.05
  done
  return 1
}

wait_for_pane_text() {
  local expected="$1"
  for _ in $(seq 1 200); do
    tmux -L "$TMUX_SOCKET" capture-pane -p >"$PANE_CAPTURE"
    grep -Fq -- "$expected" "$PANE_CAPTURE" && return 0
    sleep 0.05
  done
  return 1
}

if ! wait_for_file "$CONTROL_DIR/ready" || ! wait_for_pane_text "history-30"; then
  echo "TUI interaction probe did not render initial history" >&2
  cat "$PANE_CAPTURE" >&2 || true
  exit 1
fi

tmux -L "$TMUX_SOCKET" send-keys -l "draft"
if ! wait_for_pane_text "> draft"; then
  echo "TUI interaction probe did not render the input draft" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi

: >"$CONTROL_DIR/emit-first"
if ! wait_for_pane_text "stream-first"; then
  echo "intermediate streaming frame was not visible before final delta" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi
if grep -Fq -- "stream-first-final" "$PANE_CAPTURE"; then
  echo "final delta appeared before its signal" >&2
  exit 1
fi

: >"$CONTROL_DIR/emit-final"
if ! wait_for_pane_text "stream-first-final"; then
  echo "final streaming frame was not visible" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi

if ! grep -Fq -- "history-30" "$PANE_CAPTURE"; then
  echo "latest history was not visible before mouse wheel input" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi

tmux -L "$TMUX_SOCKET" send-keys -l $'\033[<64;40;6M'
for _ in $(seq 1 200); do
  tmux -L "$TMUX_SOCKET" capture-pane -p >"$PANE_CAPTURE"
  if ! grep -Fq -- "history-30" "$PANE_CAPTURE"; then
    break
  fi
  sleep 0.05
done
if grep -Fq -- "history-30" "$PANE_CAPTURE"; then
  echo "mouse wheel did not move the visible history window" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi
if ! grep -Fq -- "> draft" "$PANE_CAPTURE"; then
  echo "mouse wheel changed the input draft" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi

tmux -L "$TMUX_SOCKET" send-keys C-u
if ! wait_for_pane_text "> |"; then
  echo "TUI interaction probe did not clear the draft before exit" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi
tmux -L "$TMUX_SOCKET" send-keys C-c
if ! wait_for_pane_text "LYPI_TUI_INTERACTION_EXIT=0"; then
  echo "TUI interaction probe did not exit cleanly" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi

echo "tui interaction PTY passed"
