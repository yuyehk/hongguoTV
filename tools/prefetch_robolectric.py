"""Populate Robolectric's Maven cache before tests, with bounded network retries.

Robolectric 4.14.1 / Android 34 tests use Android 14; Android 15 is its newest
resource SDK. Keep these coordinates in sync when upgrading Robolectric/SDKs.
Pinned SHA-512 values match both Maven Central and Google's Central mirror.
Cache hits are rehashed; partial downloads never become usable Maven artifacts.
"""
from pathlib import Path
import argparse
import hashlib
import os
import subprocess
import tempfile
import time


REPOSITORIES = (
    "https://repo.maven.apache.org/maven2",
    # https://docs.cloud.google.com/docs/buildpacks/java#google_cloud_hosted_maven_central_mirror
    "https://maven-central.storage-download.googleapis.com/maven2",
)
ARTIFACTS = {
    "14-robolectric-10818077-i7": {
        "pom": "fb97a3028bb1eb4c4b3d1ab9c23c8b6b245842139f76194afc16afe002a212c2f93baff400a83cb6f35e525d0753e5b13aeda172fbdf0f628b25d21aa4a065d6",
        "jar": "7cdc0b1306e7865f1acd25a363073149654caee5b8e278c5ae37ff4724185724eff39f0b0111800b521e640bb28698d9d2e655134a801569a0f07470e7b5f455",
    },
    "15-robolectric-12650502-i7": {
        "pom": "2fd37e590a1cc4e661975041a53a307f27e01aa56a4f391325d6644a435d46ce194f1ee9f8acaf8dfd164f8a9fe9b86fe2a0045b515964a3e8d7f02042732c3c",
        "jar": "87bdd0341838f5f630e1ff412ab29cb2f06e0f8cb7751f14679d11b4ee6084873001f8f6a8581ae6e139fa7c9a654b632412827430f984ebecc3c2588674f7e7",
    },
}
MAX_ATTEMPTS = 4
TOTAL_SECONDS = 480


def valid_file(path, expected):
    if not path.is_file():
        return False
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha512").hexdigest() == expected


def fetch(relative, target, expected, deadline):
    target.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".prefetch-", dir=target.parent) as temporary:
        staging = Path(temporary) / "download"
        for attempt in range(MAX_ATTEMPTS):
            remaining = int(deadline - time.monotonic())
            if remaining <= 0:
                raise RuntimeError("Robolectric prefetch exceeded its eight-minute budget")
            url = REPOSITORIES[attempt % len(REPOSITORIES)] + "/" + relative
            timeout = min(90, remaining)
            print(f"Fetching {target.name}, attempt {attempt + 1}/{MAX_ATTEMPTS}", flush=True)
            result = subprocess.run(
                ["curl", "--fail", "--location", "--silent", "--show-error",
                 "--proto", "=https", "--proto-redir", "=https",
                 "--connect-timeout", str(min(15, timeout)), "--max-time", str(timeout),
                 "--output", str(staging), url],
                capture_output=True, text=True, timeout=timeout + 5,
            )
            if result.returncode == 0:
                if not valid_file(staging, expected):
                    raise RuntimeError(f"SHA-512 mismatch: {target.name}")
                os.replace(staging, target)
                return
            print(f"Download failed ({result.returncode}): {result.stderr.strip()}", flush=True)
            if attempt + 1 < MAX_ATTEMPTS:
                time.sleep(min(2 ** (attempt + 1), max(0, deadline - time.monotonic())))
        raise RuntimeError(f"Robolectric artifact unavailable after {MAX_ATTEMPTS} attempts: {target.name}")


def populate(cache, check_only=False):
    deadline = time.monotonic() + TOTAL_SECONDS
    for version, files in ARTIFACTS.items():
        for extension, expected in files.items():
            relative = (f"org/robolectric/android-all-instrumented/{version}/"
                        f"android-all-instrumented-{version}.{extension}")
            target = cache / relative
            if not valid_file(target, expected):
                if check_only:
                    raise RuntimeError(f"Missing or invalid cached artifact: {target.name}")
                fetch(relative, target, expected, deadline)
            sidecar = Path(str(target) + ".sha512")
            if check_only:
                if not sidecar.is_file() or sidecar.read_text(encoding="ascii").strip() != expected:
                    raise RuntimeError(f"Missing or invalid SHA-512 sidecar: {target.name}")
            else:
                sidecar.write_text(expected, encoding="ascii")
            print(f"Verified {target.name}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path, default=os.environ.get("HONGGUOTV_TEST_MAVEN_CACHE"))
    parser.add_argument("--check-only", action="store_true", help="Verify existing cache without writes/network")
    args = parser.parse_args()
    if args.cache_dir is None:
        parser.error("Set HONGGUOTV_TEST_MAVEN_CACHE or pass --cache-dir")
    populate(args.cache_dir, args.check_only)


if __name__ == "__main__":
    main()
