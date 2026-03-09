#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
OUT_FILE=${OUT_FILE:-"$ROOT_DIR/compile_commands.json"}
AM_HOME_DIR=${AM_HOME:-/home/lj/ysyx-workbench/abstract-machine}
ARCH_NAME=${ARCH:-riscv32im-ysyxsoc}
AMTEST_DIR=${AMTEST_DIR:-/home/lj/ysyx-workbench/am-kernels/tests/am-tests}
MAINARGS_VALUE=${MAINARGS:-v}
CLEAN_BUILD=${CLEAN_BUILD:-1}

run_bear() {
  bear --append --output "$OUT_FILE" -- "$@"
}

if ! command -v bear >/dev/null 2>&1; then
  echo "error: bear not found" >&2
  exit 1
fi

rm -f "$OUT_FILE"

if [[ "$CLEAN_BUILD" == "1" ]]; then
  make -C "$ROOT_DIR/sim_soc" clean
  make -C "$AM_HOME_DIR/am" ARCH="$ARCH_NAME" clean
  make -C "$AM_HOME_DIR/klib" ARCH="$ARCH_NAME" clean
  make -C "$AMTEST_DIR" ARCH="$ARCH_NAME" clean
fi

run_bear make -C "$ROOT_DIR/sim_soc" sim-nvboard
run_bear make -C "$AM_HOME_DIR/am" ARCH="$ARCH_NAME" archive
run_bear make -C "$AM_HOME_DIR/klib" ARCH="$ARCH_NAME" archive
run_bear make -C "$AMTEST_DIR" ARCH="$ARCH_NAME" insert-arg mainargs="$MAINARGS_VALUE"

python3 - "$OUT_FILE" <<'PY'
import json
import os
import sys
from collections import OrderedDict

path = sys.argv[1]
with open(path) as f:
    data = json.load(f)

unique = OrderedDict()
for entry in data:
    file_path = os.path.realpath(entry["file"])
    normalized = dict(entry)
    normalized["file"] = file_path
    unique[file_path] = normalized

with open(path, "w") as f:
    json.dump(list(unique.values()), f, indent=2)
    f.write("\n")

print(f"wrote {len(unique)} entries to {path}")
PY

printf 'compile_commands: %s\n' "$OUT_FILE"
