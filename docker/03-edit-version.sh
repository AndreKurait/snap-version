#!/usr/bin/env bash
# Step 3 of the repro: edit the version fields in the snapshot metadata
# so the 2.19.0 target will accept it.
#
# What changes:
#   - index-0 (plain JSON manifest): snapshots[].version "2.19.4" -> "2.19.0"
#   - snap-<uuid>.dat (Smile + codec): snapshot.version_id 136408227 -> 136407827
#
# Computed via: version_id = (major*1_000_000 + minor*10_000 + revision*100 + 99) ^ 0x08000000
#   2.19.4 => 2190499 ^ 0x08000000 = 136408227
#   2.19.0 => 2190099 ^ 0x08000000 = 136407827
set -euo pipefail
cd "$(dirname "$0")"

REPO=s3://snapshots/version-repro
ENDPOINT=http://localhost:9000
ACCESS_KEY=minioadmin
SECRET_KEY=minioadmin
SMILE_BIN="$(cd .. && pwd)/build/install/smile-snapshot-editor/bin/smile-snapshot-editor"

COMMON=( --repo "$REPO" --region us-east-1 --endpoint "$ENDPOINT"
         --access-key "$ACCESS_KEY" --secret-key "$SECRET_KEY" --path-style )

WORK="$(mktemp -d)"
echo "==> Working dir: $WORK"

# Find the snapshot uuid
SNAP_UUID=$("$SMILE_BIN" ls "${COMMON[@]}" 2>/dev/null \
  | awk '$1=="snap-v2194" { for (i=1;i<=NF;i++) if ($i ~ /^uuid=/) { sub(/uuid=/,"",$i); print $i; exit } }')
echo "==> Snapshot UUID: $SNAP_UUID"

# ---------------- 3a. Patch snap-<uuid>.dat (Smile + codec) ----------------
echo
echo "==> Pulling snap-${SNAP_UUID}.dat"
"$SMILE_BIN" pull "${COMMON[@]}" --key "snap-${SNAP_UUID}.dat" --out-dir "$WORK"
JSON_FILE="$WORK/snap-${SNAP_UUID}.dat.json"
SIDE_FILE="$WORK/snap-${SNAP_UUID}.dat.sidecar.json"

echo "==> BEFORE: version_id field:"
grep version_id "$JSON_FILE"

python3 - "$JSON_FILE" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f: tree = json.load(f)
# Snapshot info is { "snapshot": { "version_id": ..., ... } }
snap = tree["snapshot"]
old = snap.get("version_id")
new = 136407827  # 2.19.0
snap["version_id"] = new
print(f"version_id: {old} -> {new}")
with open(p, 'w') as f: json.dump(tree, f, indent=2)
PY

echo "==> AFTER:  version_id field:"
grep version_id "$JSON_FILE"

echo "==> Pushing patched snap-*.dat"
"$SMILE_BIN" push "${COMMON[@]}" --json "$JSON_FILE" --sidecar "$SIDE_FILE"

# ---------------- 3b. Patch index-N (plain JSON) ----------------
echo
echo "==> Patching index-0 manifest (plain JSON, NO codec frame)"
# Direct AWS S3 round-trip — index-N is plain JSON.
AWS_ACCESS_KEY_ID="$ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$SECRET_KEY" \
  aws --endpoint-url "$ENDPOINT" --region us-east-1 \
      s3 cp s3://snapshots/version-repro/index-0 "$WORK/index-0.json" --no-progress

echo "==> BEFORE:"
python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print([s["version"] for s in d["snapshots"]])' "$WORK/index-0.json"

python3 - "$WORK/index-0.json" <<'PY'
import json, sys
p = sys.argv[1]
with open(p) as f: tree = json.load(f)
for s in tree.get("snapshots", []):
    if s.get("version") == "2.19.4":
        s["version"] = "2.19.0"
with open(p, 'w') as f: json.dump(tree, f, indent=2)
PY

echo "==> AFTER:"
python3 -c 'import json,sys;d=json.load(open(sys.argv[1]));print([s["version"] for s in d["snapshots"]])' "$WORK/index-0.json"

AWS_ACCESS_KEY_ID="$ACCESS_KEY" AWS_SECRET_ACCESS_KEY="$SECRET_KEY" \
  aws --endpoint-url "$ENDPOINT" --region us-east-1 \
      s3 cp "$WORK/index-0.json" s3://snapshots/version-repro/index-0 --no-progress

echo
echo "==> Verifying via the editor's ls:"
"$SMILE_BIN" ls "${COMMON[@]}"
