#!/usr/bin/env bash
# Runs the server with environment variables loaded from .env (see .env.example for the template).
# Blocks until Ctrl-C, same as `./gradlew run` directly.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
else
  echo "No .env found — copy .env.example to .env to customize BRINDEX_DB_PATH/PORT. Running with defaults." >&2
fi

exec ./gradlew run
