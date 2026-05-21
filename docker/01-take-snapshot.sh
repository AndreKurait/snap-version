#!/usr/bin/env bash
# Step 1 of the version-mismatch repro: take a snapshot on the source 2.19.4 cluster.
set -euo pipefail
SRC="http://localhost:9200"
INDEX="movies"

# Cleanup any prior state
curl -fsS -XDELETE "$SRC/$INDEX" 2>/dev/null || true
curl -fsS -XDELETE "$SRC/_snapshot/repo-source/snap-v2194" 2>/dev/null || true

echo "==> Creating index $INDEX on source cluster (2.19.4)"
curl -fsS -XPUT "$SRC/$INDEX" -H 'Content-Type: application/json' -d '{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
  "mappings": {
    "properties": {
      "title":  { "type": "text" },
      "year":   { "type": "integer" },
      "rating": { "type": "float" }
    }
  }
}' >/dev/null

curl -fsS -XPOST "$SRC/$INDEX/_bulk?refresh=true" -H 'Content-Type: application/json' --data-binary '
{"index":{"_id":"1"}}
{"title":"The Matrix","year":1999,"rating":8.7}
{"index":{"_id":"2"}}
{"title":"Inception","year":2010,"rating":8.8}
{"index":{"_id":"3"}}
{"title":"Interstellar","year":2014,"rating":8.6}
' >/dev/null

curl -fsS -XPUT "$SRC/_snapshot/repo-source" -H 'Content-Type: application/json' -d '{
  "type": "s3", "settings": { "bucket": "snapshots", "base_path": "version-repro" }
}' >/dev/null

echo "==> Taking snapshot snap-v2194"
curl -fsS -XPUT "$SRC/_snapshot/repo-source/snap-v2194?wait_for_completion=true" \
  -H 'Content-Type: application/json' \
  -d "{\"indices\":\"$INDEX\",\"include_global_state\":true}" >/dev/null

echo "==> Snapshot info on source:"
curl -fsS "$SRC/_snapshot/repo-source/snap-v2194" | python3 -m json.tool
