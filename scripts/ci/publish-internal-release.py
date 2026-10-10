"""Publish already signed artifacts. No build tools or signing secrets are needed here."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


class GitHub:
    def __init__(self, repository):
        self.repository = repository

    def run(self, *args, payload=None):
        return subprocess.run(
            ["gh", *args], input=payload, capture_output=True, check=True
        ).stdout

    def api(self, path, method="GET", payload=None, optional=False):
        args = ["api", f"repos/{self.repository}/{path}", "--method", method]
        if payload is not None:
            args += ["--input", "-"]
        try:
            return json.loads(self.run(*args, payload=json.dumps(payload).encode() if payload is not None else None))
        except subprocess.CalledProcessError as error:
            if optional and b"HTTP 404" in error.stderr:
                return None
            raise

    def upload(self, tag, files):
        self.run("release", "upload", tag, *(str(file) for file in files),
                 "--repo", self.repository, "--clobber")

    def download(self, asset_id):
        return self.run("api", f"repos/{self.repository}/releases/assets/{asset_id}",
                        "--header", "Accept: application/octet-stream")


def release_version(release):
    if release is None:
        return 0
    if not re.fullmatch(r"internal-\d+\.\d+", release["tag_name"]):
        raise ValueError("Latest release is not an AnaAlarm internal release; refusing to replace it.")
    match = re.search(r"<!-- anaalarm-version-code: (\d+) -->", release.get("body", ""))
    if not match:
        raise ValueError("Latest internal release has no version code.")
    return int(match[1])


def verify_assets(github, release, files):
    assets = {asset["name"]: asset for asset in release["assets"]}
    for file in files:
        asset = assets.get(file.name)
        if asset is None or asset["state"] != "uploaded" or asset["size"] != file.stat().st_size:
            raise ValueError(f"Missing or incomplete release asset: {file.name}")
        if hashlib.sha256(github.download(asset["id"])).digest() != hashlib.sha256(file.read_bytes()).digest():
            raise ValueError(f"Release asset checksum differs: {file.name}")


def publish(github, artifact_dir, environment):
    if environment.get("GITHUB_EVENT_NAME") not in ("push", "workflow_dispatch") or environment.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Only trusted main push/manual runs may publish.")
    sha = environment["GITHUB_SHA"]
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Expected a full tested commit SHA.")
    metadata = dict(line.split("=", 1) for line in (artifact_dir / "build-info.txt").read_text().splitlines())
    run = int(environment["GITHUB_RUN_NUMBER"])
    attempt = int(environment["GITHUB_RUN_ATTEMPT"])
    if run < 1 or not 1 <= attempt <= 99:
        raise ValueError("Invalid workflow run/attempt.")
    version = run * 100 + attempt
    if version > 2_100_000_000:
        raise ValueError("Version code exceeds Android limit.")
    expected = {"application_id": "com.anaalarm.internal", "commit_sha": sha,
                "version_code": str(version), "workflow_run_number": str(run),
                "workflow_run_attempt": str(attempt), "build_type": "internal", "minified": "true"}
    if any(metadata.get(key) != value for key, value in expected.items()):
        raise ValueError("Signed artifact metadata does not match this tested workflow run.")
    artifact_name = metadata["artifact"]
    if not re.fullmatch(r"AnaAlarm-internal-[A-Za-z0-9._-]+\.apk", artifact_name):
        raise ValueError("Unsafe artifact filename.")
    apk = artifact_dir / artifact_name
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    checksum = (artifact_dir / f"{artifact_name}.sha256").read_text().split()
    if checksum != [digest, artifact_name]:
        raise ValueError("Signed artifact checksum does not match.")
    if release_version(github.api("releases/latest", optional=True)) > version:
        return "Skipped older build; newest download is unchanged."
    tag = f"internal-{run}.{attempt}"
    body = (
        f"<!-- anaalarm-version-code: {version} -->\n"
        f"AnaAlarm Internal · `{metadata['version_name']}` · version code `{version}`\n\n"
        f"Tested commit: `{sha}`\n"
        f"[Quality and signing run](https://github.com/{github.repository}/actions/runs/{environment['GITHUB_RUN_ID']})\n\n"
        "On your phone, tap **AnaAlarm-internal.apk** below, open the download, and confirm Install. "
        "If asked, allow installation from this browser or file manager. "
        "An existing AnaAlarm Internal installation updates in place and keeps its data. "
        "The production app uses a different package and remains separate.\n\n"
        "The checksum and build-info.txt identify the verified build. "
        "Device diagnostics are available under **Settings → Sentinel**.\n"
    )
    with tempfile.TemporaryDirectory(prefix="anaalarm-release-") as temporary:
        directory = Path(temporary)
        stable_apk = directory / "AnaAlarm-internal.apk"
        stable_apk.write_bytes(apk.read_bytes())
        stable_checksum = directory / "AnaAlarm-internal.apk.sha256"
        stable_checksum.write_text(f"{digest}  {stable_apk.name}\n", encoding="utf-8", newline="\n")
        stable_info = directory / "build-info.txt"
        stable_info.write_bytes((artifact_dir / "build-info.txt").read_bytes())
        files = [stable_apk, stable_checksum, stable_info]
        release = github.api(f"releases/tags/{tag}", optional=True)
        if release is None:
            release = github.api("releases", "POST", {
                "tag_name": tag, "target_commitish": sha, "name": f"AnaAlarm Internal {run}.{attempt}",
                "body": body, "draft": True, "prerelease": False, "make_latest": "false"
            })
        if release_version(release) != version or release.get("target_commitish") != sha:
            raise ValueError("Existing release belongs to a different build.")
        if release["draft"]:
            github.upload(tag, files)
            release = github.api(f"releases/{release['id']}")
        verify_assets(github, release, files)
        # The workflow serializes this entire operation; re-check before changing latest.
        if release_version(github.api("releases/latest", optional=True)) > version:
            return "Skipped older build before publication."
        github.api(f"releases/{release['id']}", "PATCH", {"draft": False, "make_latest": "true"})
    return f"Published {tag}: https://github.com/{github.repository}/releases/latest/download/AnaAlarm-internal.apk"


if __name__ == "__main__":
    result = publish(GitHub(os.environ["GITHUB_REPOSITORY"]), Path("signed-input"), os.environ)
    print(result)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(result + "\n")
