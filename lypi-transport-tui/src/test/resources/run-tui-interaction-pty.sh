#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

if ! command -v tmux >/dev/null 2>&1; then
  echo "tmux is required for TUI interaction PTY" >&2
  exit 1
fi
if ! command -v script >/dev/null 2>&1; then
  echo "script is required for attached tmux client input" >&2
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
  'printf "SHELL_INTERACTION_SENTINEL\n"; TERM=xterm-256color java -cp %q cn.lypi.transport.tui.TuiInteractionPtyProbe %q; status=$?; printf "LYPI_TUI_INTERACTION_EXIT=%%s\n" "$status"; IFS= read -r shell_input; printf "SHELL_INPUT=%%s\n" "$shell_input"; sleep 20' \
  "$PROBE_CLASSPATH" "$CONTROL_DIR"
tmux -L "$TMUX_SOCKET" new-session -d -x 80 -y 12 "$PTY_COMMAND"
tmux -L "$TMUX_SOCKET" set-option -g mouse on

PANE_CAPTURE="$TMP_DIR/pane.txt"
FULL_CAPTURE="$TMP_DIR/full-pane.txt"
CLIENT_LOG="$TMP_DIR/client.log"
PANE_TARGET="0:0.0"

capture_visible() {
  tmux -L "$TMUX_SOCKET" capture-pane -p -t "$PANE_TARGET" >"$PANE_CAPTURE"
}

capture_full() {
  tmux -L "$TMUX_SOCKET" capture-pane -p -S - -t "$PANE_TARGET" >"$FULL_CAPTURE"
}

wait_for_file() {
  local path="$1"
  for _ in $(seq 1 300); do
    [[ -f "$path" ]] && return 0
    sleep 0.05
  done
  return 1
}

wait_for_pane_text() {
  local expected="$1"
  for _ in $(seq 1 300); do
    capture_visible
    grep -Fq -- "$expected" "$PANE_CAPTURE" && return 0
    sleep 0.05
  done
  return 1
}

wait_for_full_text() {
  local expected="$1"
  for _ in $(seq 1 300); do
    capture_full
    grep -Fq -- "$expected" "$FULL_CAPTURE" && return 0
    sleep 0.05
  done
  return 1
}

assert_exact_count() {
  local expected="$1"
  local wanted="$2"
  local actual
  actual="$(grep -Fxc -- "$expected" "$FULL_CAPTURE" || true)"
  if [[ "$actual" -ne "$wanted" ]]; then
    echo "expected $wanted exact full-capture line(s), found $actual: $expected" >&2
    cat "$FULL_CAPTURE" >&2
    exit 1
  fi
}

assert_substring_count() {
  local expected="$1"
  local wanted="$2"
  local actual
  actual="$(grep -Fc -- "$expected" "$FULL_CAPTURE" || true)"
  if [[ "$actual" -ne "$wanted" ]]; then
    echo "expected $wanted full-capture match(es), found $actual: $expected" >&2
    cat "$FULL_CAPTURE" >&2
    exit 1
  fi
}

assert_committed_history_once() {
  local index suffix sentinel
  for index in $(seq 1 42); do
    printf -v suffix '%03d' "$index"
    sentinel="history-sentinel-$suffix"
    assert_exact_count "$sentinel" 1
  done
}

if ! wait_for_file "$CONTROL_DIR/ready" || ! wait_for_full_text "history-sentinel-042"; then
  echo "TUI interaction probe did not render initial history" >&2
  cat "$FULL_CAPTURE" >&2 || true
  exit 1
fi
assert_committed_history_once
assert_exact_count "SHELL_INTERACTION_SENTINEL" 1
assert_substring_count "ses_1" 1

tmux -L "$TMUX_SOCKET" send-keys -t "$PANE_TARGET" -l "draft"
if ! wait_for_pane_text "> draft|"; then
  echo "TUI interaction probe did not render the input draft" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi

: >"$CONTROL_DIR/emit-intermediate"
if ! wait_for_file "$CONTROL_DIR/intermediate-emitted" \
    || ! wait_for_pane_text "stream-intermediate"; then
  echo "intermediate streaming frame was not visible" >&2
  cat "$PANE_CAPTURE" >&2 || true
  exit 1
fi
capture_full
assert_committed_history_once
assert_exact_count "stream-intermediate" 1
assert_exact_count "> draft|" 1
assert_substring_count "ses_1" 1

tmux -L "$TMUX_SOCKET" resize-window -t 0 -x 60 -y 9
: >"$CONTROL_DIR/resize-small"
if ! wait_for_file "$CONTROL_DIR/resize-small-processed"; then
  echo "TUI interaction probe did not process 60x9 resize" >&2
  capture_full
  cat "$FULL_CAPTURE" >&2
  exit 1
fi
if [[ "$(tmux -L "$TMUX_SOCKET" display-message -p -t "$PANE_TARGET" '#{pane_width}x#{pane_height}')" != "60x9" ]]; then
  echo "tmux pane did not reach 60x9" >&2
  exit 1
fi
capture_full
assert_committed_history_once
assert_exact_count "stream-intermediate" 1
assert_exact_count "> draft|" 1
assert_substring_count "ses_1" 1

tmux -L "$TMUX_SOCKET" resize-window -t 0 -x 80 -y 12
: >"$CONTROL_DIR/resize-large"
if ! wait_for_file "$CONTROL_DIR/resize-large-processed"; then
  echo "TUI interaction probe did not process 80x12 resize" >&2
  capture_full
  cat "$FULL_CAPTURE" >&2
  exit 1
fi
if [[ "$(tmux -L "$TMUX_SOCKET" display-message -p -t "$PANE_TARGET" '#{pane_width}x#{pane_height}')" != "80x12" ]]; then
  echo "tmux pane did not return to 80x12" >&2
  exit 1
fi
capture_full
assert_committed_history_once
assert_exact_count "stream-intermediate" 1
assert_exact_count "> draft|" 1
assert_substring_count "ses_1" 1

: >"$CONTROL_DIR/emit-final"
if ! wait_for_file "$CONTROL_DIR/final-emitted" \
    || ! wait_for_pane_text "stream-intermediate-final"; then
  echo "final streaming frame was not visible" >&2
  cat "$PANE_CAPTURE" >&2 || true
  exit 1
fi
capture_full
assert_committed_history_once
assert_exact_count "stream-intermediate" 0
assert_exact_count "stream-intermediate-final" 1
assert_exact_count "> draft|" 1
assert_substring_count "ses_1" 1

printf -v ATTACH_COMMAND \
  'stty rows 12 cols 80; exec tmux -L %q attach-session -t 0' \
  "$TMUX_SOCKET"
{
  sleep 0.5
  printf '\033[<64;40;6M'
  sleep 0.15
  printf '\033[<64;40;6M'
  sleep 0.15
  printf '\002d'
} | TERM=xterm-256color timeout 8 script -q -e -c "$ATTACH_COMMAND" "$CLIENT_LOG" >/dev/null

pane_state="$(tmux -L "$TMUX_SOCKET" display-message -p -t "$PANE_TARGET" \
  '#{pane_in_mode} #{scroll_position} #{pane_mode}')"
read -r pane_in_mode scroll_position pane_mode <<<"$pane_state"
if [[ "$pane_in_mode" != "1" \
    || "$pane_mode" != "copy-mode" \
    || ! "$scroll_position" =~ ^[0-9]+$ \
    || "$scroll_position" -le 0 ]]; then
  echo "real mouse wheel did not enter tmux copy-mode: $pane_state" >&2
  cat "$CLIENT_LOG" >&2
  exit 1
fi

tmux -L "$TMUX_SOCKET" send-keys -t "$PANE_TARGET" -X cancel
for _ in $(seq 1 100); do
  [[ "$(tmux -L "$TMUX_SOCKET" display-message -p -t "$PANE_TARGET" '#{pane_in_mode}')" == "0" ]] && break
  sleep 0.05
done
if [[ "$(tmux -L "$TMUX_SOCKET" display-message -p -t "$PANE_TARGET" '#{pane_in_mode}')" != "0" ]]; then
  echo "tmux pane did not leave copy-mode" >&2
  exit 1
fi
if ! wait_for_pane_text "> draft|"; then
  echo "native scrollback interaction changed the TUI draft" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi
capture_full
assert_committed_history_once
assert_exact_count "stream-intermediate" 0
assert_exact_count "stream-intermediate-final" 1
assert_exact_count "> draft|" 1
assert_substring_count "ses_1" 1

tmux -L "$TMUX_SOCKET" send-keys -t "$PANE_TARGET" C-u
if ! wait_for_pane_text "> |"; then
  echo "TUI interaction probe did not clear the draft before exit" >&2
  cat "$PANE_CAPTURE" >&2
  exit 1
fi
capture_full
assert_committed_history_once
assert_exact_count "> draft|" 0
assert_exact_count "> |" 1
assert_substring_count "ses_1" 1

tmux -L "$TMUX_SOCKET" send-keys -t "$PANE_TARGET" C-c
if ! wait_for_full_text "LYPI_TUI_INTERACTION_EXIT=0"; then
  echo "TUI interaction probe did not exit cleanly" >&2
  cat "$FULL_CAPTURE" >&2
  exit 1
fi
tmux -L "$TMUX_SOCKET" send-keys -t "$PANE_TARGET" -l "shell-after-exit"
tmux -L "$TMUX_SOCKET" send-keys -t "$PANE_TARGET" Enter
if ! wait_for_full_text "SHELL_INPUT=shell-after-exit"; then
  echo "shell did not take over input after TUI close" >&2
  cat "$FULL_CAPTURE" >&2
  exit 1
fi

capture_full
assert_committed_history_once
assert_exact_count "SHELL_INTERACTION_SENTINEL" 1
assert_exact_count "stream-intermediate" 0
assert_exact_count "stream-intermediate-final" 1
assert_exact_count "> draft|" 0
assert_exact_count "> |" 0
assert_substring_count "ses_1" 0
assert_exact_count "LYPI_TUI_INTERACTION_EXIT=0" 1
assert_exact_count "SHELL_INPUT=shell-after-exit" 1

echo "tui interaction PTY passed"
