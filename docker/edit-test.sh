#!/usr/bin/env bash
# Proves the *edit* path (not just round-trip):
#   1. Pull global metadata
#   2. Modify a field (rewrite cluster_uuid_history_id) inside the JSON
#   3. Push back, restore, confirm modified value lands in the cluster.
set -euo pipefail
cd "$(dirname "$0")"

REGION="us-east-1"
ENDPOINT="http://localhost:9000"
ACCESS_KEY="minioadmin"
SECRET_KEY="minioadmin"
REPO_URI="s3://snapshots/repo"
SNAPSHOT_NAME="snap-edit"
INDEX_NAME="edit-target"

SMILE_BIN="$(cd .. && pwd)/build/install/smile-snapshot-editor/bin/smile-snapshot-editor"
COMMON=( --repo "$REPO_URI" --region "$REGION" --endpoint "$ENDPOINT"
         --access-key "$ACCESS_KEY" --secret-key "$SECRET_KEY" --path-style )

# Cleanup any previous run remnants
curl -fsS -XDELETE "http://localhost:9200/$INDEX_NAME" 2>/dev/null || true
curl -fsS -XDELETE "http://localhost:9200/${INDEX_NAME}-restored" 2>/dev/null || true
curl -fsS -XDELETE "http://localhost:9200/_snapshot/test-repo/$SNAPSHOT_NAME" 2>/dev/null || true

curl -fsS -XPUT "http://localhost:9200/$INDEX_NAME" \
  -H 'Content-Type: application/json' \
  -d '{"settings":{"number_of_shards":1,"number_of_replicas":0,"index":{"refresh_interval":"1s"}}}' >/dev/null

curl -fsS -XPOST "http://localhost:9200/$INDEX_NAME/_doc?refresh=true" \
  -H 'Content-Type: application/json' \
  -d '{"hello":"world"}' >/dev/null

echo "==> Take snapshot $SNAPSHOT_NAME"
curl -fsS -XPUT "http://localhost:9200/_snapshot/test-repo/$SNAPSHOT_NAME?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d "{\"indices\":\"$INDEX_NAME\",\"include_global_state\":false}" >/dev/null

# Find this snapshot's UUID
SNAP_UUID="$(
  "$SMILE_BIN" ls "${COMMON[@]}" \
    | awk -v name="$SNAPSHOT_NAME" '$1==name { for (i=1;i<=NF;i++) if ($i ~ /^uuid=/) { sub(/uuid=/,"",$i); print $i; exit } }'
)"
echo "==> Snapshot UUID: $SNAP_UUID"

# Find the index's UUID
IDX_UUID="$(
  "$SMILE_BIN" ls "${COMMON[@]}" \
    | awk -v name="$INDEX_NAME" '$1==name { for (i=1;i<=NF;i++) if ($i ~ /^uuid=/) { sub(/uuid=/,"",$i); print $i; exit } }'
)"
echo "==> Index UUID: $IDX_UUID"

# Pull the index metadata. Need the file ID from index-N manifest.
# Easier: use the index-metadata file under indices/<uuid>/, pick the only meta-*.dat there.
INDEX_META_KEY="$(
  AWS_ACCESS_KEY_ID="$ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$SECRET_KEY" \
  aws --endpoint-url "$ENDPOINT" --region "$REGION" \
      --no-cli-pager s3api list-objects-v2 \
      --bucket snapshots --prefix "repo/indices/$IDX_UUID/" \
      --query 'Contents[?contains(Key, `meta-`)].Key' --output text 2>/dev/null \
    | tr '\t' '\n' | head -1 | sed 's|^repo/||'
)"
[[ -n "$INDEX_META_KEY" ]] || { echo "ERR: could not find index meta key"; exit 1; }
echo "==> Index meta key: $INDEX_META_KEY"

WORK="$(mktemp -d)"
"$SMILE_BIN" pull "${COMMON[@]}" --key "$INDEX_META_KEY" --out-dir "$WORK"

JSON_FILE="$(ls "$WORK"/*.json | grep -v sidecar)"
SIDECAR_FILE="$WORK/$(basename "$JSON_FILE" .json).sidecar.json"
echo "==> JSON file:   $JSON_FILE"
echo "==> Sidecar:     $SIDECAR_FILE"

# Modify a setting that survives restore: index.refresh_interval 1s -> 30s.
python3 - "$JSON_FILE" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f: tree = json.load(f)

# ES index metadata stores settings as either nested {"index": {"refresh_interval": ...}}
# OR flattened {"index.refresh_interval": ...} — handle both.
def set_refresh(node, value="30s"):
    if isinstance(node, dict):
        for k, v in list(node.items()):
            if k == "settings" and isinstance(v, dict):
                # flat form
                if "index.refresh_interval" in v:
                    v["index.refresh_interval"] = value
                    return True
                # nested form
                idx = v.get("index")
                if isinstance(idx, dict) and "refresh_interval" in idx:
                    idx["refresh_interval"] = value
                    return True
                # not found here — inject it (flat)
                v["index.refresh_interval"] = value
                return True
            if set_refresh(v, value):
                return True
    elif isinstance(node, list):
        for x in node:
            if set_refresh(x, value):
                return True
    return False

ok = set_refresh(tree)
print("modified:", ok)
with open(p, 'w') as f: json.dump(tree, f, indent=2)
PY

echo "==> Pushing modified index metadata"
"$SMILE_BIN" push "${COMMON[@]}" --json "$JSON_FILE" --sidecar "$SIDECAR_FILE"

echo "==> Restoring snapshot to a new index name"
RESTORED="${INDEX_NAME}-restored"
curl -fsS -XPOST "http://localhost:9200/_snapshot/test-repo/$SNAPSHOT_NAME/_restore?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d "{\"indices\":\"$INDEX_NAME\",\"rename_pattern\":\"(.+)\",\"rename_replacement\":\"\$1-restored\"}"
echo

echo "==> Verifying refresh_interval is now 30s"
INTERVAL="$(curl -fsS "http://localhost:9200/$RESTORED/_settings" \
  | python3 -c 'import sys,json; d=json.load(sys.stdin); k=list(d)[0]; print(d[k]["settings"]["index"]["refresh_interval"])')"
echo "==> refresh_interval = $INTERVAL (expected 30s)"
[[ "$INTERVAL" == "30s" ]] || { echo "FAIL"; exit 1; }
echo "==> SUCCESS — edit was honored on restore"
