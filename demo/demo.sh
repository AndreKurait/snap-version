#!/usr/bin/env bash
# Records a clean asciinema cast of the full version-downgrade workflow.
#
# What it does, in one shot:
#   1. (assumes containers are up via `docker compose up -d --wait` already)
#   2. take a 2.19.4 snapshot
#   3. attempt restore on 2.19.0 -- shows the rejection
#   4. run `snap-version version --to 2.19.0 --yes` -- shows the rewrite
#   5. retry restore on 2.19.0 -- shows the success
#   6. count documents -- shows the data is intact
#
# Usage:
#   docker compose -f docker/docker-compose.yml up -d --wait
#   ./gradlew installDist
#   ./demo/record.sh   # writes demo.cast + demo.gif at the repo root

set -euo pipefail
cd "$(dirname "$0")/.."

# --- helpers -----------------------------------------------------------------

green() { printf '\033[1;32m%s\033[0m\n' "$*"; }
cyan()  { printf '\033[1;36m%s\033[0m\n' "$*"; }
gray()  { printf '\033[0;37m%s\033[0m\n' "$*"; }
say()   { printf '\033[1;33m# %s\033[0m\n' "$*"; sleep 1.2; }

SE="$(pwd)/build/install/snap-version/bin/snap-version"
S3=( --repo s3://snapshots/version-repro --region us-east-1
     --endpoint http://localhost:9000
     --access-key minioadmin --secret-key minioadmin --path-style )

# --- the actual demo ---------------------------------------------------------

clear
green "==> snap-version demo"
sleep 0.6
say  "Goal: restore an OpenSearch 2.19.4 snapshot onto a 2.19.0 cluster."
say  "OpenSearch normally rejects this. snap-version edits the recorded"
say  "version inside the snapshot metadata so the target accepts it."
sleep 0.8

cyan "$ # Reset state from any previous demo"
curl -fsS -XDELETE http://localhost:9200/movies                              >/dev/null 2>&1 || true
curl -fsS -XDELETE 'http://localhost:9200/_snapshot/repo-source/snap-v2194'  >/dev/null 2>&1 || true
curl -fsS -XDELETE 'http://localhost:9200/_snapshot/repo-source'             >/dev/null 2>&1 || true
curl -fsS -XDELETE http://localhost:9201/movies                              >/dev/null 2>&1 || true
curl -fsS -XDELETE 'http://localhost:9201/_snapshot/repo-target'             >/dev/null 2>&1 || true
sleep 0.4

# 1. Index docs and snapshot on the source 2.19.4 cluster
say "Index 3 movies on the source cluster (OpenSearch 2.19.4) and take a snapshot."
cyan '$ curl -X PUT localhost:9200/movies ...'
curl -fsS -XPUT 'http://localhost:9200/movies' -H 'Content-Type: application/json' -d '{
  "settings": { "number_of_shards": 1, "number_of_replicas": 0 },
  "mappings": { "properties": {
    "title": {"type":"text"}, "year": {"type":"integer"}, "rating": {"type":"float"}
  }}
}' >/dev/null
curl -fsS -XPOST 'http://localhost:9200/movies/_bulk?refresh=true' \
  -H 'Content-Type: application/json' --data-binary '
{"index":{"_id":"1"}}
{"title":"The Matrix","year":1999,"rating":8.7}
{"index":{"_id":"2"}}
{"title":"Inception","year":2010,"rating":8.8}
{"index":{"_id":"3"}}
{"title":"Interstellar","year":2014,"rating":8.6}
' >/dev/null
curl -fsS -XPUT 'http://localhost:9200/_snapshot/repo-source' -H 'Content-Type: application/json' \
  -d '{"type":"s3","settings":{"bucket":"snapshots","base_path":"version-repro"}}' >/dev/null
curl -fsS -XPUT 'http://localhost:9200/_snapshot/repo-source/snap-v2194?wait_for_completion=true' \
  -H 'Content-Type: application/json' \
  -d '{"indices":"movies","include_global_state":true}' >/dev/null
green "   snapshot created"
sleep 0.6

# 2. Try the restore on the lower-version target
say "Try to restore on the target cluster (OpenSearch 2.19.0)..."
cyan '$ curl -X POST localhost:9201/_snapshot/.../snap-v2194/_restore'
curl -fsS -XPUT 'http://localhost:9201/_snapshot/repo-target' -H 'Content-Type: application/json' \
  -d '{"type":"s3","settings":{"bucket":"snapshots","base_path":"version-repro"}}' >/dev/null
HTTP_CODE=$(curl -sS -o /tmp/demo-fail.json -w '%{http_code}' \
  -XPOST 'http://localhost:9201/_snapshot/repo-target/snap-v2194/_restore?wait_for_completion=true' \
  -H 'Content-Type: application/json' -d '{"indices":"movies","include_global_state":false}')
printf '\033[1;31mHTTP %s\033[0m  ' "$HTTP_CODE"
python3 -c 'import json;d=json.load(open("/tmp/demo-fail.json"));print(d["error"]["reason"])'
sleep 1.6

# 3. Inspect with snap-version
say "Inspect the snapshot's recorded version with snap-version:"
cyan '$ snap-version version --repo s3://... --show'
$SE version "${S3[@]}" --show 2>&1 | grep -v INFO
sleep 1.4

# 4. Downgrade
say "Rewrite version 2.19.4 -> 2.19.0 in both index-N and snap-*.dat:"
cyan '$ snap-version version --repo s3://... --to 2.19.0 --yes'
$SE version "${S3[@]}" --to 2.19.0 --yes 2>&1 | grep -v INFO
sleep 1.4

# 5. Retry restore
say "Retry the restore on 2.19.0..."
cyan '$ curl -X POST localhost:9201/_snapshot/.../snap-v2194/_restore'
curl -fsS -XDELETE 'http://localhost:9201/_snapshot/repo-target' >/dev/null
curl -fsS -XPUT 'http://localhost:9201/_snapshot/repo-target' -H 'Content-Type: application/json' \
  -d '{"type":"s3","settings":{"bucket":"snapshots","base_path":"version-repro"}}' >/dev/null
HTTP_CODE=$(curl -sS -o /tmp/demo-ok.json -w '%{http_code}' \
  -XPOST 'http://localhost:9201/_snapshot/repo-target/snap-v2194/_restore?wait_for_completion=true' \
  -H 'Content-Type: application/json' -d '{"indices":"movies","include_global_state":false}')
printf '\033[1;32mHTTP %s\033[0m  ' "$HTTP_CODE"
python3 -c 'import json;d=json.load(open("/tmp/demo-ok.json"));print("restored",d["snapshot"]["indices"],"shards",d["snapshot"]["shards"])'
sleep 1.0

# 6. Verify
say "Verify the documents landed on 2.19.0:"
cyan '$ curl localhost:9201/movies/_count'
curl -sS http://localhost:9201/movies/_count
echo
sleep 1.4

green "==> snap-version downgraded a 2.19.4 snapshot so 2.19.0 could restore it."
sleep 1.5
