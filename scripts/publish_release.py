#!/usr/bin/env python3
"""Publish a complete, verified draft without replacing a release or its assets."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import quote


class ReleaseError(RuntimeError):
    pass


def gh_api(method, endpoint, *, data=None, upload=None):
    command = [
        "gh", "api", "--hostname", "github.com", "--method", method,
        "-H", "Accept: application/vnd.github+json",
        "-H", "X-GitHub-Api-Version: 2022-11-28", endpoint,
    ]
    payload = None
    if upload is not None:
        command.extend(["-H", "Content-Type: application/octet-stream", "--input", str(upload)])
    elif data is not None:
        command.extend(["--input", "-"])
        payload = json.dumps(data)
    result = subprocess.run(command, input=payload, text=True, capture_output=True, check=True)
    return json.loads(result.stdout)


def list_all(api, endpoint):
    items = []
    page = 1
    while True:
        result = api("GET", f"{endpoint}?per_page=100&page={page}")
        if not isinstance(result, list) or len(result) > 100 or any(not isinstance(item, dict) for item in result):
            raise ReleaseError("GitHub returned an invalid paginated response")
        items.extend(result)
        if len(result) < 100:
            return items
        page += 1


def validate_assets(directory, version):
    names = {f"snap-version-{version}{suffix}" for suffix in (".tar", ".zip", "-all.jar")}
    expected = names | {"SHA256SUMS.txt"}
    if {path.name for path in directory.iterdir()} != expected:
        raise ReleaseError("Distribution must contain exactly the tar, zip, fat JAR, and SHA256SUMS.txt")
    assets = {}
    for name in sorted(expected):
        path = directory / name
        if path.is_symlink() or not path.is_file() or path.stat().st_size == 0:
            raise ReleaseError(f"Asset must be a nonempty regular file: {name}")
        assets[name] = {
            "path": path,
            "size": path.stat().st_size,
            "digest": f"sha256:{hashlib.sha256(path.read_bytes()).hexdigest()}",
        }
    entries = {}
    for line in (directory / "SHA256SUMS.txt").read_text(encoding="utf-8").splitlines():
        match = re.fullmatch(r"([0-9a-fA-F]{64})  (?:\./)?([^\s/]+)", line)
        if not match or match[2] in entries:
            raise ReleaseError("Invalid or duplicate checksum entry")
        entries[match[2]] = f"sha256:{match[1].lower()}"
    if set(entries) != names or any(entries[name] != assets[name]["digest"] for name in names):
        raise ReleaseError("Checksums must match every distribution file exactly")
    return assets


def check_tag(api, repo, tag, commit):
    result = api("GET", f"repos/{repo}/commits/{quote(tag, safe='')}")
    if not isinstance(result, dict) or result.get("sha") != commit:
        raise ReleaseError("Remote tag does not point to the commit that was built")


def check_asset(result, name, expected):
    if not isinstance(result, dict) or (
        result.get("name") != name
        or result.get("state") != "uploaded"
        or result.get("size") != expected["size"]
        or result.get("digest") != expected["digest"]
    ):
        raise ReleaseError(f"Uploaded asset was not verified: {name}")


def publish_release(repo, tag, commit, directory, notes, api=gh_api):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ReleaseError("Expected a GitHub owner/repository")
    if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?", tag):
        raise ReleaseError("Expected a version tag such as v0.2.0")
    if not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ReleaseError("Expected the full commit SHA that was built")
    version = tag[1:]
    assets = validate_assets(directory, version)
    body = notes.read_text(encoding="utf-8").replace("@TAG@", tag).replace("@VERSION@", version)
    prerelease = "-" in version.split("+", 1)[0]

    # List all releases, including authenticated drafts; an API failure is fatal.
    releases = list_all(api, f"repos/{repo}/releases")
    if any(not isinstance(release.get("tag_name"), str) for release in releases):
        raise ReleaseError("GitHub returned a release without its tag name")
    if any(release.get("tag_name") == tag for release in releases):
        raise ReleaseError("A release or draft already exists for this tag; refusing to replace it")
    check_tag(api, repo, tag, commit)
    created = api("POST", f"repos/{repo}/releases", data={
        "tag_name": tag,
        "target_commitish": commit,
        "name": tag,
        "body": body,
        "draft": True,
        "prerelease": prerelease,
        "make_latest": "false",
        "generate_release_notes": True,
    })
    if not isinstance(created, dict) or (
        type(created.get("id")) is not int or created["id"] <= 0
        or created.get("draft") is not True or created.get("tag_name") != tag
    ):
        raise ReleaseError("GitHub did not return the expected new draft release")
    release_id = created["id"]
    print(f"Created draft release {release_id}; uploads will be verified before publication.", flush=True)
    for name, expected in assets.items():
        # Use the returned numeric ID, never the tag lookup or an overwrite flag.
        uploaded = api(
            "POST",
            f"https://uploads.github.com/repos/{repo}/releases/{release_id}/assets?name={quote(name, safe='')}",
            upload=expected["path"],
        )
        check_asset(uploaded, name, expected)

    remote = list_all(api, f"repos/{repo}/releases/{release_id}/assets")
    if len(remote) != len(assets) or {item.get("name") for item in remote} != set(assets):
        raise ReleaseError("The draft does not contain exactly the expected assets")
    for asset in remote:
        check_asset(asset, asset["name"], assets[asset["name"]])
    current = api("GET", f"repos/{repo}/releases/{release_id}")
    if not isinstance(current, dict) or (
        current.get("id") != release_id or current.get("draft") is not True
        or current.get("tag_name") != tag
    ):
        raise ReleaseError("The draft changed before publication")
    check_tag(api, repo, tag, commit)
    published = api("PATCH", f"repos/{repo}/releases/{release_id}", data={
        "draft": False,
        "make_latest": "false" if prerelease else "true",
    })
    if not isinstance(published, dict) or (
        published.get("id") != release_id or published.get("draft") is not False
        or published.get("tag_name") != tag
    ):
        raise ReleaseError("Publication response could not be verified; inspect the release before retrying")
    print(f"Published {tag} as release {release_id} with {len(assets)} verified assets.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--dist", type=Path, default=Path("dist"))
    parser.add_argument("--notes", type=Path, default=Path(".github/release-notes.md"))
    args = parser.parse_args()
    try:
        publish_release(args.repository, args.tag, args.commit, args.dist, args.notes)
    except (ReleaseError, OSError, ValueError, subprocess.CalledProcessError) as error:
        print(f"Release stopped: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
