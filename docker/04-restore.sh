#!/usr/bin/env bash
# Step 4: now that the version metadata says 2.19.0, the target should accept the restore.
set -euo pipefail
TGT="http://localhost:9201"

echo "==> Cleanup any prior state on target"
curl -fsS -XDELETE "$TGT/movies" 2>/dev/null || true
curl -fsS -XDELETE "$TGT/_snapshot/repo-target" 2>/dev/null || true

echo "==> Re-registering target's view of the repo (forces a re-read of index-N)"
curl -fsS -XPUT "$TGT/_snapshot/repo-target" -H 'Content-Type: application/json' -d '{
  "type": "s3", "settings": { "bucket": "snapshots", "base_path": "version-repro" }
}' >/dev/null

echo "==> Snapshot status as visible to target now (should say 2.19.0):"
curl -fsS "$TGT/_snapshot/repo-target/_all" | python3 -m json.tool | head -20

echo
echo "==> Attempting restore"
HTTP_CODE=$(curl -sS -o /tmp/restore-resp2.json -w '%{http_code}' \
  -XPOST "$TGT/_snapshot/repo-target/snap-v2194/_restore?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d '{"indices":"movies","include_global_state":false}')
echo "HTTP $HTTP_CODE"
python3 -m json.tool /tmp/restore-resp2.json

echo
echo "==> Verify documents are readable:"
curl -fsS "$TGT/movies/_count?refresh=true"
echo
curl -fsS "$TGT/movies/_search?size=10" | python3 -m json.tool | head -50
