# Changelog

## 0.2.0 (2026-11-02)

### Rewrite validation and inspection

- Add `version --dry-run --to VERSION` to validate and preview a selected rewrite without writing.
- Validate all selected metadata before writing; reject missing, inconsistent, unsupported, or changed metadata and unknown snapshot names.
- Preserve the detected OpenSearch or legacy Elasticsearch version-ID encoding. Leave already-matching metadata byte-for-byte unchanged.
- Read every S3 listing page and reject a truncated response that cannot advance its continuation token.
- Reject local keys that escape the repository root or traverse symlinks. Replace individual local files with atomic renames.
- Validate framed metadata lengths and checksums before decoding.

### Packaging and tests

- Report the packaged version consistently from the installed launcher and fat JAR.
- Build and smoke-test Java 21 JVM distributions and the fat JAR in CI and the tag-triggered release workflow.
- Add synthetic rewrite, filesystem, pagination, and CLI regression coverage.
- Restore a reproducible MinIO fixture for container tests. The OpenSearch suite covers the specific 2.19.4 → 2.19.0 pair, including incremental snapshots; the separate Elasticsearch 7.17.28 → 7.17.0 fixture is not part of normal CI.

### Upgrading from 0.1.x

Install a new distribution or rebuild the source; an existing downloaded binary is not updated automatically. A bare `--snapshot` now requires at least one name, unknown names fail, and invalid selected metadata stops the rewrite instead of being silently skipped.

Work on a complete offline copy with an untouched backup. These changes do not convert index data or establish general downgrade compatibility. A whole repository rewrite is still not transactional: a write failure or concurrent writer can leave partial changes, and the tool does not provide automatic backups or rollback.

Earlier release notes and assets are available in GitHub Releases.
