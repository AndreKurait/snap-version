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
a Docker environment supported by the pinned Testcontainers version. Unit tests
must not contact AWS or use real repository credentials. Report the environment
and exact engine pair when sharing an e2e result; do not generalize one fixture's
success to arbitrary downgrade compatibility.

The e2e tasks first build a local MinIO image from the pinned source commit for
`RELEASE.2024-09-13T20-26-02Z`; its former published image is unavailable. The first
build needs internet access to fetch the Go build image and modules and can take
several minutes. Subsequent builds reuse Docker's layer cache. This historical
MinIO fixture is for disposable tests only. Both e2e tasks build it automatically;
`./gradlew buildTestMinio` also prepares it before running tests from an IDE.

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

Update the default version in `build.gradle`, the changelog, and the README
together. Run `./gradlew --no-daemon test installDist distZip distTar fatJar`,
then check `--help` and `--version` using both the installed launcher and fat
JAR. The versions must match the intended release.

Merge the reviewed release preparation and wait for that commit's unit and
OpenSearch end-to-end CI checks to pass. The separately gated Elasticsearch
fixture does not run in normal CI; do not describe it as validated unless it
was run for the stated versions and environment.

When publication is authorized, create a new `vX.Y.Z` tag at the tested commit
and push it. **Pushing the tag publishes artifacts automatically.** The Release
workflow checks out that exact tag, reruns unit tests, and builds
platform-independent JVM `.tar` and `.zip` distributions plus a fat JAR and
checksums. These artifacts need Java 21 or later; they are not separate native
binaries for each OS/architecture.

The manual Release workflow requires an existing tag and also publishes; it is
not a preview. Never move an existing version tag or rerun publication for an
already published release. Verify the new assets and checksum file after
publication, and include compatibility and offline-copy requirements in the
release notes.

## Code style

- Java 21, 4-space indent, 120-char lines.
- `./gradlew check` runs the default verification tasks; Docker e2e tasks are separate.
- For rewrite changes, prove that malformed input causes no writes and document
  any remaining partial-write boundary. Work only with synthetic/offline copies.
- For release changes, smoke-test both the installed launcher and the fat JAR.
- Tests double as documentation: a new feature should add a test in
  `CliE2ETest` (drives the actual command in-process) so future contributors
  can read it as a usage example.
