#!/usr/bin/env bash
# Step 2 of the repro: try to restore the 2.19.4 snapshot on the 2.19.0 target.
# This SHOULD fail with a version-incompatibility error.
set -euo pipefail
TGT="http://localhost:9201"

# Reset any prior state on target
curl -fsS -XDELETE "$TGT/movies" 2>/dev/null || true
curl -fsS -XDELETE "$TGT/_snapshot/repo-target" 2>/dev/null || true

echo "==> Registering read-only s3 repo on target 2.19.0"
curl -fsS -XPUT "$TGT/_snapshot/repo-target" -H 'Content-Type: application/json' -d '{
  "type": "s3",
  "settings": { "bucket": "snapshots", "base_path": "version-repro", "readonly": "false" }
}' >/dev/null

echo "==> Listing snapshots visible to target:"
curl -fsS "$TGT/_snapshot/repo-target/_all" | python3 -m json.tool | head -40

echo
echo "==> Attempting restore (this should fail with version mismatch)"
HTTP_CODE=$(curl -sS -o /tmp/restore-resp.json -w '%{http_code}' \
  -XPOST "$TGT/_snapshot/repo-target/snap-v2194/_restore?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d '{"indices":"movies","include_global_state":false}')
echo "HTTP $HTTP_CODE"
python3 -m json.tool /tmp/restore-resp.json
