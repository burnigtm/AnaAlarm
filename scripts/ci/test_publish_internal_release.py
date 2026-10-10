import hashlib
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-internal-release.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


class FakeGitHub:
    repository = "burnigtm/AnaAlarm"

    def __init__(self):
        self.latest = None
        self.release = None
        self.bytes = {}
        self.fail_upload = False
        self.corrupt = False
        self.published = False
        self.uploads = 0

    def api(self, path, method="GET", payload=None, optional=False):
        if path == "releases/latest":
            return self.latest
        if method == "POST":
            self.release = dict(payload, id=1, assets=[])
        elif method == "PATCH":
            self.release.update(payload)
            self.latest = self.release.copy()
            self.published = True
        return self.release

    def upload(self, tag, files):
        self.uploads += 1
        self.release["assets"] = []
        for index, file in enumerate(files):
            if self.fail_upload and index == 1:
                raise RuntimeError("Upload interrupted")
            self.bytes[index] = file.read_bytes()
            self.release["assets"].append({"id": index, "name": file.name,
                                          "state": "uploaded", "size": len(self.bytes[index])})

    def download(self, asset_id):
        return b"corrupted" if self.corrupt else self.bytes[asset_id]


class PublishTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.directory = Path(self.temporary.name)
        self.sha = "a" * 40
        self.env = dict(GITHUB_EVENT_NAME="push", GITHUB_REF="refs/heads/main", GITHUB_SHA=self.sha,
                        GITHUB_RUN_NUMBER="7", GITHUB_RUN_ATTEMPT="2", GITHUB_RUN_ID="123")
        name = "AnaAlarm-internal-7.2-aaaaaaaaaaaa.apk"
        (self.directory / name).write_bytes(b"signed apk")
        digest = hashlib.sha256(b"signed apk").hexdigest()
        (self.directory / f"{name}.sha256").write_text(f"{digest}  {name}\n")
        metadata = dict(artifact=name, application_id="com.anaalarm.internal", commit_sha=self.sha,
                        version_code="702", version_name="1.0-internal.7.2", workflow_run_number="7",
                        workflow_run_attempt="2", build_type="internal", minified="true")
        (self.directory / "build-info.txt").write_text("".join(f"{key}={value}\n" for key, value in metadata.items()))
        self.github = FakeGitHub()

    def publish(self):
        return publisher.publish(self.github, self.directory, self.env)

    def test_complete_assets_published_at_tested_commit(self):
        self.publish()
        self.assertTrue(self.github.published)
        self.assertEqual(self.github.release["target_commitish"], self.sha)
        self.assertEqual({asset["name"] for asset in self.github.release["assets"]},
                         {"AnaAlarm-internal.apk", "AnaAlarm-internal.apk.sha256", "build-info.txt"})
        self.assertTrue(self.github.bytes[1].endswith(b"  AnaAlarm-internal.apk\n"))

    def test_upload_failure_stays_draft_and_retry_recovers(self):
        self.github.fail_upload = True
        with self.assertRaises(RuntimeError):
            self.publish()
        self.assertTrue(self.github.release["draft"])
        self.assertFalse(self.github.published)
        self.github.fail_upload = False
        self.publish()
        self.assertTrue(self.github.published)

    def test_published_retry_verifies_without_clobbering_assets(self):
        self.publish()
        self.publish()
        self.assertEqual(self.github.uploads, 1)

    def test_corrupt_remote_asset_never_publishes(self):
        self.github.corrupt = True
        with self.assertRaises(ValueError):
            self.publish()
        self.assertFalse(self.github.published)

    def test_older_run_never_changes_latest(self):
        self.github.latest = {"tag_name": "internal-9.1", "body": "<!-- anaalarm-version-code: 901 -->"}
        self.assertIn("Skipped", self.publish())
        self.assertIsNone(self.github.release)

    def test_pull_requests_and_non_main_cannot_publish(self):
        for event, ref in [("pull_request", "refs/heads/main"), ("workflow_dispatch", "refs/heads/feature")]:
            self.env.update(GITHUB_EVENT_NAME=event, GITHUB_REF=ref)
            with self.assertRaises(ValueError):
                self.publish()
        self.assertFalse(self.github.published)

    def test_metadata_and_local_checksum_must_match(self):
        self.env["GITHUB_SHA"] = "b" * 40
        with self.assertRaises(ValueError):
            self.publish()
        self.env["GITHUB_SHA"] = self.sha
        next(self.directory.glob("*.sha256")).write_text("incorrect checksum")
        with self.assertRaises(ValueError):
            self.publish()

    def test_existing_release_must_match_commit(self):
        self.github.release = dict(id=1, tag_name="internal-7.2", target_commitish="b" * 40,
                                  body="<!-- anaalarm-version-code: 702 -->", draft=True, assets=[])
        with self.assertRaises(ValueError):
            self.publish()

    def test_missing_remote_asset_cannot_be_published(self):
        original_upload = self.github.upload
        def incomplete_upload(tag, files):
            original_upload(tag, files)
            self.github.release["assets"].pop()
        self.github.upload = incomplete_upload
        with self.assertRaises(ValueError):
            self.publish()
        self.assertTrue(self.github.release["draft"])

    def test_non_internal_latest_release_is_not_replaced(self):
        self.github.latest = {"tag_name": "v2.0", "body": "Production release"}
        with self.assertRaises(ValueError):
            self.publish()
        self.assertIsNone(self.github.release)


if __name__ == "__main__":
    unittest.main()
