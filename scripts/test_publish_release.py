import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlparse

import publish_release as release


REPO = "example/snap-version"
TAG = "v0.2.0"
COMMIT = "a" * 40
RELEASE_ID = 1234


class FakeGitHub:
    def __init__(self):
        self.calls = []
        self.releases = []
        self.uploads = []
        self.tag_checks = 0
        self.draft = {"id": RELEASE_ID, "tag_name": TAG, "draft": True}
        self.fail_upload = None
        self.upload_digest = None
        self.final_tag = COMMIT
        self.extra_asset = False

    def __call__(self, method, endpoint, **kwargs):
        self.calls.append((method, endpoint, kwargs))
        if method == "GET" and endpoint.startswith(f"repos/{REPO}/releases?"):
            page = int(parse_qs(urlparse(endpoint).query)["page"][0])
            return self.releases[(page - 1) * 100:page * 100]
        if method == "GET" and endpoint == f"repos/{REPO}/commits/{self.draft['tag_name']}":
            self.tag_checks += 1
            return {"sha": COMMIT if self.tag_checks == 1 else self.final_tag}
        if method == "POST" and endpoint == f"repos/{REPO}/releases":
            return self.draft.copy()
        if method == "POST" and endpoint.startswith(
            f"https://uploads.github.com/repos/{REPO}/releases/{RELEASE_ID}/assets?"
        ):
            path = kwargs["upload"]
            name = parse_qs(urlparse(endpoint).query)["name"][0]
            if name == self.fail_upload:
                raise subprocess.CalledProcessError(1, ["gh", "api"])
            data = path.read_bytes()
            asset = {
                "name": name, "state": "uploaded", "size": len(data),
                "digest": self.upload_digest or f"sha256:{hashlib.sha256(data).hexdigest()}",
            }
            self.uploads.append(asset.copy())
            return asset
        if method == "GET" and endpoint.startswith(f"repos/{REPO}/releases/{RELEASE_ID}/assets?"):
            return self.uploads + ([{"name": "extra"}] if self.extra_asset else [])
        if method == "GET" and endpoint == f"repos/{REPO}/releases/{RELEASE_ID}":
            return self.draft.copy()
        if method == "PATCH" and endpoint == f"repos/{REPO}/releases/{RELEASE_ID}":
            return {**self.draft, "draft": False}
        raise AssertionError(f"Unexpected API call: {method} {endpoint}")


class PublishReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dist = self.root / "dist"
        self.dist.mkdir()
        self.notes = self.root / "notes.md"
        self.notes.write_text("Install @TAG@ with version @VERSION@", encoding="utf-8")
        self.api = FakeGitHub()
        self.make_assets()

    def make_assets(self, version="0.2.0"):
        entries = []
        for suffix in (".tar", ".zip", "-all.jar"):
            path = self.dist / f"snap-version-{version}{suffix}"
            path.write_bytes(f"synthetic {suffix} fixture".encode())
            entries.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}\n")
        (self.dist / "SHA256SUMS.txt").write_text("".join(entries), encoding="utf-8")

    def publish(self, **kwargs):
        return release.publish_release(
            REPO, kwargs.get("tag", TAG), COMMIT, self.dist, self.notes, self.api,
        )

    def assert_no_writes(self):
        self.assertFalse(any(method != "GET" for method, _, _ in self.api.calls))

    def assert_not_published(self):
        self.assertFalse(any(method == "PATCH" for method, _, _ in self.api.calls))

    def test_complete_draft_is_uploaded_verified_and_published_by_id(self):
        self.publish()
        create = next(call for call in self.api.calls if call[:2] == ("POST", f"repos/{REPO}/releases"))
        self.assertTrue(create[2]["data"]["draft"])
        self.assertEqual("false", create[2]["data"]["make_latest"])
        self.assertEqual(COMMIT, create[2]["data"]["target_commitish"])
        self.assertEqual("Install v0.2.0 with version 0.2.0", create[2]["data"]["body"])
        self.assertEqual(4, len(self.api.uploads))
        self.assertEqual(2, self.api.tag_checks)
        self.assertEqual(
            ("PATCH", f"repos/{REPO}/releases/{RELEASE_ID}", {"data": {"draft": False, "make_latest": "true"}}),
            self.api.calls[-1],
        )
        self.assertEqual(("GET", f"repos/{REPO}/commits/{TAG}"), self.api.calls[-2][:2])

    def test_existing_public_release_and_draft_are_both_refused(self):
        for draft in (False, True):
            with self.subTest(draft=draft):
                self.api = FakeGitHub()
                self.api.releases = [{"tag_name": TAG, "draft": draft}]
                with self.assertRaisesRegex(release.ReleaseError, "already exists"):
                    self.publish()
                self.assert_no_writes()

    def test_existing_release_on_second_page_is_refused(self):
        self.api.releases = [{"tag_name": f"v0.1.{number}"} for number in range(100)]
        self.api.releases.append({"tag_name": TAG, "draft": True})
        with self.assertRaisesRegex(release.ReleaseError, "already exists"):
            self.publish()
        self.assertEqual(2, len(self.api.calls))
        self.assert_no_writes()

    def test_release_listing_failure_does_not_create_anything(self):
        for error in (subprocess.CalledProcessError(1, ["gh", "api"]), ValueError("invalid JSON")):
            with self.subTest(error=type(error).__name__):
                with patch.object(self, "api", side_effect=error) as api:
                    with self.assertRaises(type(error)):
                        self.publish()
                    self.assertEqual("GET", api.call_args.args[0])
                    self.assertEqual(1, api.call_count)

    def test_malformed_release_listing_is_refused(self):
        for listing in ({}, [None], [{}]):
            with self.subTest(listing=listing):
                with patch.object(self, "api", return_value=listing) as api:
                    with self.assertRaises(release.ReleaseError):
                        self.publish()
                    self.assertEqual(1, api.call_count)

    def test_mismatched_tag_before_creation_is_refused(self):
        self.api.tag_checks = 1
        self.api.final_tag = "b" * 40
        with self.assertRaisesRegex(release.ReleaseError, "Remote tag"):
            self.publish()
        self.assert_no_writes()

    def test_moved_tag_after_upload_leaves_draft_unpublished(self):
        self.api.final_tag = "b" * 40
        with self.assertRaisesRegex(release.ReleaseError, "Remote tag"):
            self.publish()
        self.assertEqual(4, len(self.api.uploads))
        self.assert_not_published()

    def test_failed_upload_leaves_draft_unpublished(self):
        self.api.fail_upload = "snap-version-0.2.0.tar"
        with self.assertRaises(subprocess.CalledProcessError):
            self.publish()
        self.assertGreater(len(self.api.uploads), 0)
        self.assert_not_published()

    def test_unverified_upload_leaves_draft_unpublished(self):
        self.api.upload_digest = "sha256:" + "0" * 64
        with self.assertRaisesRegex(release.ReleaseError, "Uploaded asset"):
            self.publish()
        self.assert_not_published()

    def test_unexpected_remote_asset_prevents_publication(self):
        self.api.extra_asset = True
        with self.assertRaisesRegex(release.ReleaseError, "exactly the expected assets"):
            self.publish()
        self.assert_not_published()

    def test_invalid_draft_response_prevents_upload_or_publication(self):
        self.api.draft["id"] = "not-a-number"
        with self.assertRaisesRegex(release.ReleaseError, "expected new draft"):
            self.publish()
        self.assertEqual([], self.api.uploads)
        self.assert_not_published()

    def test_changed_draft_prevents_publication(self):
        original_api = self.api

        def changing_api(method, endpoint, **kwargs):
            result = original_api(method, endpoint, **kwargs)
            if method == "GET" and endpoint == f"repos/{REPO}/releases/{RELEASE_ID}":
                result["draft"] = False
            return result

        self.api = changing_api
        with self.assertRaisesRegex(release.ReleaseError, "draft changed"):
            self.publish()
        self.assertFalse(any(method == "PATCH" for method, _, _ in original_api.calls))

    def test_prerelease_does_not_become_latest(self):
        for path in self.dist.iterdir():
            path.unlink()
        self.make_assets("0.2.0-rc.1")
        self.api.draft["tag_name"] = "v0.2.0-rc.1"
        self.publish(tag="v0.2.0-rc.1")
        create = next(call for call in self.api.calls if call[:2] == ("POST", f"repos/{REPO}/releases"))
        self.assertTrue(create[2]["data"]["prerelease"])
        self.assertEqual("false", self.api.calls[-1][2]["data"]["make_latest"])

    def test_missing_extra_empty_and_symlink_assets_are_refused_before_api(self):
        target = self.dist / "snap-version-0.2.0.zip"
        original = target.read_bytes()
        for invalid in ("missing", "extra", "empty", "symlink"):
            with self.subTest(invalid=invalid):
                extra = self.dist / "unexpected.txt"
                if invalid == "missing":
                    target.unlink()
                elif invalid == "extra":
                    extra.write_text("extra")
                elif invalid == "empty":
                    target.write_bytes(b"")
                else:
                    target.unlink()
                    target.symlink_to(self.notes)
                with self.assertRaises(release.ReleaseError):
                    self.publish()
                self.assertEqual([], self.api.calls)
                if extra.exists():
                    extra.unlink()
                if target.is_symlink():
                    target.unlink()
                target.write_bytes(original)

    def test_wrong_duplicate_and_missing_checksums_are_refused_before_api(self):
        path = self.dist / "SHA256SUMS.txt"
        original = path.read_text()
        first = original.splitlines(keepends=True)[0]
        variants = [
            original.replace(first[:64], "0" * 64, 1),
            original + first,
            "".join(original.splitlines(keepends=True)[1:]),
            original.replace("  snap-version", "  ../snap-version", 1),
        ]
        for manifest in variants:
            with self.subTest(manifest=manifest):
                path.write_text(manifest)
                with self.assertRaises(release.ReleaseError):
                    self.publish()
                self.assertEqual([], self.api.calls)

    def test_api_json_transport_preserves_draft_boolean(self):
        with patch.object(release.subprocess, "run") as run:
            run.return_value.stdout = '{"id": 1234}'
            self.assertEqual({"id": 1234}, release.gh_api("POST", "repos/example/repo/releases", data={"draft": True}))
            self.assertEqual({"draft": True}, json.loads(run.call_args.kwargs["input"]))
            self.assertTrue(run.call_args.kwargs["check"])
            self.assertIn("--hostname", run.call_args.args[0])

    def test_api_upload_uses_file_input_without_clobber(self):
        path = self.dist / "SHA256SUMS.txt"
        with patch.object(release.subprocess, "run") as run:
            run.return_value.stdout = '{"state": "uploaded"}'
            release.gh_api("POST", "https://uploads.github.com/asset", upload=path)
            command = run.call_args.args[0]
            self.assertEqual(["--input", str(path)], command[-2:])
            self.assertNotIn("--clobber", command)
            self.assertIsNone(run.call_args.kwargs["input"])


if __name__ == "__main__":
    unittest.main()
