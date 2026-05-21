#!/usr/bin/env bash
# Records demo/demo.sh into demo/demo.cast and renders a GIF via `agg`.
#
# Requirements: asciinema, agg
#   brew install asciinema agg
#
# Usage (from repo root):
#   docker compose -f docker/docker-compose.yml up -d --wait
#   ./gradlew installDist
#   ./demo/record.sh
set -euo pipefail
cd "$(dirname "$0")/.."

mkdir -p demo
CAST=demo/demo.cast
GIF=demo/demo.gif

# Re-record (asciinema appends by default; force overwrite)
asciinema rec --overwrite \
              --command="bash demo/demo.sh" \
              --idle-time-limit=2 \
              --window-size=110x32 \
              "$CAST"

# Render to GIF. agg defaults are reasonable; adjust speed/columns if needed.
agg --speed 1.4 \
    --cols 110 --rows 32 \
    --theme monokai \
    "$CAST" "$GIF"

echo
echo "Wrote $CAST and $GIF"
ls -la "$CAST" "$GIF"
