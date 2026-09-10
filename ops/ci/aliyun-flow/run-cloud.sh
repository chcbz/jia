#!/bin/sh
set -eu

if [ "$#" -ne 4 ]; then
  echo "usage: run-cloud.sh TICKET EXPECTED_TICKET_SHA256 API_CWD RECEIPT" >&2
  exit 2
fi

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
TOOL_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/../../.." && pwd -P)

exec python3 "$TOOL_ROOT/ops/orchestration/cyf_orchestrator.py" \
  flow-remote run \
  --ticket "$1" \
  --expected-ticket-sha256 "$2" \
  --cwd "$3" \
  --receipt "$4"
