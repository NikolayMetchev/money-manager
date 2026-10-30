"""Rewrites aapt2's pins (bazel/kmp/android/pins.bzl) for MODULE.bazel's android_sdk.agp: the build AGP names in its
plugin jar's aapt2_version.properties, and each host's jar SHA-256, by downloading them (Google's Maven publishes no
checksum file). repin.sh runs it after Renovate bumps AGP."""

import hashlib
import io
import re
import sys
import urllib.request
import zipfile

MAVEN = "https://dl.google.com/android/maven2/com/android/tools/build"

module_path, pins_path = sys.argv[1], sys.argv[2]
agp = re.search(r'^android_sdk\.agp\(version = "([^"]+)"\)', open(module_path, encoding="utf-8").read(), re.M).group(1)
pins = open(pins_path, encoding="utf-8").read()


def fetch(url):
    with urllib.request.urlopen(url) as response:
        return response.read()


plugin = zipfile.ZipFile(io.BytesIO(fetch(f"{MAVEN}/gradle/{agp}/gradle-{agp}.jar")))
properties = plugin.read("com/android/build/gradle/internal/res/aapt2_version.properties").decode()
build = re.search(r"^aapt2Version=(\S+)", properties, re.M).group(1)

updated = re.sub(r'^AAPT2_BUILD = "[^"]*"', f'AAPT2_BUILD = "{build}"', pins, flags=re.M)
version = f"{agp}-{build}"


def repin(match):
    host = match.group(2)
    sha = hashlib.sha256(fetch(f"{MAVEN}/aapt2/{version}/aapt2-{version}-{host}.jar")).hexdigest()
    return match.group(1) + sha + match.group(3)


updated, count = re.subn(r'(    "(linux|osx|windows)": ")[0-9a-f]{64}(",)', repin, updated)
if count != 3:
    sys.exit(f"expected 3 aapt2 checksums in {pins_path}, found {count}")
if updated != pins:
    open(pins_path, "w", encoding="utf-8").write(updated)
    print(f"Re-pinned aapt2 {version}")
