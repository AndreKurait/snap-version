## Install

### Recommended: clone + run via Gradle (works on macOS, Linux, Windows)

```bash
(
  set -e
  git clone https://github.com/AndreKurait/snap-version
  cd snap-version
  git checkout @TAG@
  ./gradlew installDist "-Pversion=@VERSION@"
  build/install/snap-version/bin/snap-version --help
)
```

A Java installation is needed to launch Gradle. The build can
provision its configured Java 21 daemon/toolchain when necessary.
Windows users can use `gradlew.bat` and the generated `.bat` launcher.

### Or: download a prebuilt JVM distribution

```bash
(
  set -eu
  artifact="snap-version-@VERSION@.tar"
  release="https://github.com/AndreKurait/snap-version/releases/download/@TAG@"
  curl -fLO "$release/$artifact"
  curl -fLO "$release/SHA256SUMS.txt"
  awk -v file="$artifact" '$2 == "./" file || $2 == file { if (NF != 2 || length($1) != 64 || $1 ~ /[^[:xdigit:]]/) exit 1; print $1 "  " file; found++ } END { if (found != 1) exit 1 }' SHA256SUMS.txt > "$artifact.sha256"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c "$artifact.sha256"
  else
    shasum -a 256 -c "$artifact.sha256"
  fi || exit 1
  tar -xf "$artifact"
  ./snap-version-@VERSION@/bin/snap-version --help
)
```

Requires Java 21+ on `$PATH` (Temurin / Corretto / etc.).

### Or: single fat JAR

```bash
(
  set -eu
  artifact="snap-version-@VERSION@-all.jar"
  release="https://github.com/AndreKurait/snap-version/releases/download/@TAG@"
  curl -fLO "$release/$artifact"
  curl -fLO "$release/SHA256SUMS.txt"
  awk -v file="$artifact" '$2 == "./" file || $2 == file { if (NF != 2 || length($1) != 64 || $1 ~ /[^[:xdigit:]]/) exit 1; print $1 "  " file; found++ } END { if (found != 1) exit 1 }' SHA256SUMS.txt > "$artifact.sha256"
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -c "$artifact.sha256"
  else
    shasum -a 256 -c "$artifact.sha256"
  fi || exit 1
  java -jar "$artifact" --help
)
```

Each download example checks only the downloaded artifact's entry
and stops if the checksum is missing, duplicated, or incorrect.
Verification uses `sha256sum` when available and `shasum -a 256`
on macOS. These JVM artifacts require Java 21+. Metadata
rewriting does not establish downgrade compatibility; read the
README and work only on an offline copy with an untouched backup.
