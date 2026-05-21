#!/usr/bin/env bash
# End-to-end smoke test:
#   1. Spin up OpenSearch + MinIO via docker compose
#   2. Index a few documents
#   3. Take a snapshot to s3://snapshots/repo
#   4. Use the editor to `ls`, `cat` global + index metadata, `pull`, `push` (no-op edit)
#   5. Verify OpenSearch can still restore the snapshot afterwards (CRC + frame valid)
set -euo pipefail

cd "$(dirname "$0")"

REGION="${REGION:-us-east-1}"
ENDPOINT="${ENDPOINT:-http://localhost:9000}"
ACCESS_KEY="${ACCESS_KEY:-minioadmin}"
SECRET_KEY="${SECRET_KEY:-minioadmin}"
REPO_URI="${REPO_URI:-s3://snapshots/repo}"
SNAPSHOT_NAME="${SNAPSHOT_NAME:-snap-1}"
INDEX_NAME="${INDEX_NAME:-test-index}"

echo "==> Starting docker compose"
docker compose up -d --wait

echo "==> Waiting for OpenSearch"
for i in $(seq 1 60); do
  if curl -fsS "http://localhost:9200/_cluster/health?wait_for_status=yellow&timeout=2s" >/dev/null; then
    break
  fi
  sleep 2
done

echo "==> Creating index $INDEX_NAME and indexing docs"
curl -fsS -XPUT "http://localhost:9200/$INDEX_NAME" \
  -H 'Content-Type: application/json' \
  -d '{"settings":{"number_of_shards":1,"number_of_replicas":0},"mappings":{"properties":{"name":{"type":"keyword"},"value":{"type":"long"}}}}' >/dev/null
for i in 1 2 3 4 5; do
  curl -fsS -XPOST "http://localhost:9200/$INDEX_NAME/_doc" \
    -H 'Content-Type: application/json' \
    -d "{\"name\":\"doc-$i\",\"value\":$i}" >/dev/null
done
curl -fsS -XPOST "http://localhost:9200/$INDEX_NAME/_refresh" >/dev/null

echo "==> Registering snapshot repository at $REPO_URI"
# The OpenSearch S3 plugin uses the keystore credentials we set up in docker-compose.yml.
curl -fsS -XPUT "http://localhost:9200/_snapshot/test-repo" \
  -H 'Content-Type: application/json' \
  -d '{"type":"s3","settings":{"bucket":"snapshots","base_path":"repo"}}' >/dev/null

echo "==> Taking snapshot $SNAPSHOT_NAME"
curl -fsS -XPUT "http://localhost:9200/_snapshot/test-repo/$SNAPSHOT_NAME?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d "{\"indices\":\"$INDEX_NAME\",\"include_global_state\":true}" >/dev/null

echo
echo "==> Snapshot complete. Listing repo via the editor:"
SMILE_BIN="$(cd .. && pwd)/build/install/smile-snapshot-editor/bin/smile-snapshot-editor"
if [[ ! -x "$SMILE_BIN" ]]; then
  ( cd .. && ./gradlew --no-daemon installDist -q )
fi

COMMON=(
  --repo "$REPO_URI"
  --region "$REGION"
  --endpoint "$ENDPOINT"
  --access-key "$ACCESS_KEY"
  --secret-key "$SECRET_KEY"
  --path-style
)

"$SMILE_BIN" ls "${COMMON[@]}"

# Discover the snapshot's UUID (from index-N) so we can target meta-<uuid>.dat
SNAP_UUID="$(
  "$SMILE_BIN" ls "${COMMON[@]}" \
    | awk -v name="$SNAPSHOT_NAME" '$1==name { for (i=1;i<=NF;i++) if ($i ~ /^uuid=/) { sub(/uuid=/,"",$i); print $i; exit } }'
)"
echo
echo "==> Snapshot UUID: $SNAP_UUID"

echo
echo "==> cat global metadata:"
"$SMILE_BIN" cat "${COMMON[@]}" --key "meta-$SNAP_UUID.dat" | head -40

WORK="$(mktemp -d)"
echo
echo "==> pull global metadata into $WORK"
"$SMILE_BIN" pull "${COMMON[@]}" --key "meta-$SNAP_UUID.dat" --out-dir "$WORK"
ls -la "$WORK"

echo
echo "==> Round-trip push (no edit) — proves byte-identical re-encode is restorable"
"$SMILE_BIN" push "${COMMON[@]}" \
  --json     "$WORK/meta-${SNAP_UUID}.dat.json" \
  --sidecar  "$WORK/meta-${SNAP_UUID}.dat.sidecar.json"

echo
echo "==> Verify OpenSearch can still restore the (round-tripped) snapshot:"
curl -fsS -XDELETE "http://localhost:9200/$INDEX_NAME" >/dev/null
curl -fsS -XPOST "http://localhost:9200/_snapshot/test-repo/$SNAPSHOT_NAME/_restore?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d '{"indices":"'"$INDEX_NAME"'"}'
echo
COUNT="$(curl -fsS "http://localhost:9200/$INDEX_NAME/_count" | python3 -c 'import sys,json;print(json.load(sys.stdin)["count"])')"
echo "==> Restored doc count: $COUNT (expected 5)"
[[ "$COUNT" == "5" ]] || { echo "FAIL"; exit 1; }
echo "==> SUCCESS"
