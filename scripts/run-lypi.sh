#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: scripts/run-lypi.sh [--run-dir DIR] [-- APP_ARGS...]

Build lypi-boot and run the fat jar from an independent runtime directory.

Options:
  --run-dir DIR   Runtime working directory. Defaults to /tmp/lypi-run.
  -h, --help      Show this help.
USAGE
}

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
run_dir="${LYPI_RUN_DIR:-/tmp/lypi-run}"
app_args=()

if [[ -n "${LYPI_RUNTIME_CWD:-}" ]]; then
  echo "Refusing LYPI_RUNTIME_CWD override; runtime cwd is controlled by --run-dir" >&2
  exit 2
fi

while (($# > 0)); do
  case "$1" in
    --run-dir)
      shift
      if (($# == 0)); then
        echo "Missing value for --run-dir" >&2
        exit 2
      fi
      run_dir="$1"
      shift
      ;;
    --)
      shift
      app_args=("$@")
      break
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --lypi.runtime.cwd|--lypi.runtime.cwd=*)
      echo "Refusing lypi.runtime.cwd override; use --run-dir instead" >&2
      exit 2
      ;;
    *)
      if [[ "$1" == -Dlypi.runtime.cwd=* ]]; then
        echo "Refusing lypi.runtime.cwd override; use --run-dir instead" >&2
        exit 2
      fi
      app_args+=("$1")
      shift
      ;;
  esac
done

for arg in "${app_args[@]}"; do
  case "$arg" in
    --lypi.runtime.cwd|--lypi.runtime.cwd=*|-Dlypi.runtime.cwd=*)
      echo "Refusing lypi.runtime.cwd override; use --run-dir instead" >&2
      exit 2
      ;;
  esac
done

run_dir="$(realpath -m "$run_dir")"

probe="$run_dir"
while [[ "$probe" != "/" && ! -e "$probe" ]]; do
  probe="$(dirname "$probe")"
done
if [[ -e "$probe" ]] && git -C "$probe" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  echo "Refusing to run inside Git worktree: $run_dir" >&2
  echo "Choose an independent directory, for example: /tmp/lypi-run" >&2
  exit 2
fi

mkdir -p "$run_dir"
run_dir="$(cd "$run_dir" && pwd -P)"

mvn -f "$repo_root/pom.xml" -pl lypi-boot -am package -DskipTests

jar="$repo_root/lypi-boot/target/lypi-boot-0.0.1-SNAPSHOT.jar"
if [[ ! -f "$jar" ]]; then
  echo "Boot jar not found after build: $jar" >&2
  exit 1
fi

cd "$run_dir"
exec java -jar "$jar" --lypi.runtime.cwd="$run_dir" "${app_args[@]}"
