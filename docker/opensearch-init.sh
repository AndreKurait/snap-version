#!/bin/bash
# Custom OpenSearch entrypoint: install repository-s3 + seed S3 credentials in keystore,
# then hand off to the standard entrypoint.
set -e

cd /usr/share/opensearch

if ! ls plugins | grep -q repository-s3; then
  echo "[init] installing repository-s3 plugin"
  ./bin/opensearch-plugin install --batch repository-s3
fi

if [ ! -f config/opensearch.keystore ]; then
  echo "[init] creating keystore"
  bin/opensearch-keystore create
fi

echo "[init] adding S3 access key + secret key to keystore"
echo "${MINIO_USER:-minioadmin}" | bin/opensearch-keystore add --stdin --force s3.client.default.access_key
echo "${MINIO_PASS:-minioadmin}" | bin/opensearch-keystore add --stdin --force s3.client.default.secret_key

echo "[init] handing off to opensearch-docker-entrypoint.sh"
exec ./opensearch-docker-entrypoint.sh
