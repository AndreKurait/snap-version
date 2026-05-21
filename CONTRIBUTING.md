# Contributing to `snap-version`

Thanks for opening this — patches, bug reports, and questions are all welcome.

## Code of conduct

Be kind, on-topic, and concrete. No personal attacks. Reviewer/maintainer
discretion in edge cases.

## Quick start

```bash
git clone https://github.com/AndreKurait/snap-version
cd snap-version
./gradlew test                  # unit + CLI tests (~10s, no Docker needed)
./gradlew installDist           # build runnable distribution
build/install/snap-version/bin/snap-version --help
```

## End-to-end tests

The e2e suite (`./gradlew e2eTest`) spins up two OpenSearch versions + MinIO via
Testcontainers and exercises the whole "fail → rewrite → success" arc. It needs
a working Docker daemon (Docker Desktop, Rancher Desktop, Colima, podman with
docker-compat all work). Testcontainers 1.21.4+ is required for Docker 29.x.

## Submitting a change

1. Fork, create a feature branch.
2. Run `./gradlew test e2eTest` locally.
3. Open a PR. Keep PRs small and focused; if you're touching the codec/format
   layer, please add a test that pins down the byte-level claim.
4. CI will run the unit suite on every PR. The e2e suite runs on `main`
   pushes and on PRs labeled `run-e2e` (to keep PR turnaround fast).

## Adding support for a new ES/OS version

If you need to handle a version that's not in `VersionCodec`'s known-good
table, add a row to `VersionCodecTest.encodingMatchesKnownOpenSearchIds` and
verify the value against a real cluster's `version_id` in `snap-*.dat`.

## Releasing (maintainers)

Tag a release: `git tag v0.X.Y && git push origin v0.X.Y`. The Release workflow
will build the cross-platform distributions (Linux x64/arm64, macOS arm64/x64,
Windows x64) and attach them to the GitHub Release.

## Code style

- Java 21, 4-space indent, 120-char lines.
- `./gradlew check` runs all linters/tests we care about.
- Tests double as documentation: a new feature should add a test in
  `CliE2ETest` (drives the actual command in-process) so future contributors
  can read it as a usage example.
