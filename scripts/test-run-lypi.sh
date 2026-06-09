#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
script="$repo_root/scripts/run-lypi.sh"
tmp_root="$(mktemp -d "${TMPDIR:-/tmp}/lypi-run-script-test.XXXXXX")"
repo_run_dir="$repo_root/.script-test-run-dir"
rm -rf "$repo_run_dir"
trap 'rm -rf "$tmp_root" "$repo_run_dir"' EXIT

run_expect_failure() {
  local label="$1"
  shift
  local output
  set +e
  output="$("$script" "$@" 2>&1)"
  local status=$?
  set -e
  if [[ "$status" -eq 0 ]]; then
    echo "Expected failure for $label" >&2
    exit 1
  fi
  printf '%s' "$output"
}

assert_contains() {
  local output="$1"
  local expected="$2"
  if [[ "$output" != *"$expected"* ]]; then
    echo "Expected output to contain: $expected" >&2
    echo "Actual output:" >&2
    echo "$output" >&2
    exit 1
  fi
}

output="$(run_expect_failure "repository run dir" --run-dir "$repo_run_dir" -- --lypi.runtime.session-id=script-test)"
assert_contains "$output" "Refusing to run inside Git worktree"
[[ ! -e "$repo_run_dir" ]]

foreign_repo="$tmp_root/foreign-repo"
mkdir -p "$foreign_repo"
git -C "$foreign_repo" init -q
output="$(run_expect_failure "foreign git worktree run dir" --run-dir "$foreign_repo/run" -- --lypi.runtime.session-id=script-test)"
assert_contains "$output" "Refusing to run inside Git worktree"

output="$(run_expect_failure "runtime cwd option" --run-dir "$tmp_root/run" -- --lypi.runtime.cwd="$repo_root" --lypi.runtime.session-id=script-test)"
assert_contains "$output" "Refusing lypi.runtime.cwd override"
[[ ! -e "$tmp_root/run" ]]

set +e
output="$(LYPI_RUNTIME_CWD="$repo_root" "$script" --run-dir "$tmp_root/run-env" -- --lypi.runtime.session-id=script-test 2>&1)"
status=$?
set -e
if [[ "$status" -eq 0 ]]; then
  echo "Expected failure for runtime cwd env" >&2
  exit 1
fi
assert_contains "$output" "Refusing LYPI_RUNTIME_CWD override"
[[ ! -e "$tmp_root/run-env" ]]
