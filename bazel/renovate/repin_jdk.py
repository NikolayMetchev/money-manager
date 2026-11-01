"""Rewrites MODULE.bazel's JDK_ARCHIVES for its JDK version, each platform's newest Azul Zulu build of that release
with its SHA-256 from Azul's metadata API, and .bazelrc's java_runtime_version to the JDK's feature release (repin.sh
runs it after Renovate bumps JDK)."""

import json
import re
import sys
import urllib.parse
import urllib.request

API = "https://api.azul.com/metadata/v1/zulu/packages/"
# JDK_ARCHIVES' keys (Bazel's os_cpu) → Azul's os, arch and archive type.
PLATFORMS = {
    "linux_aarch64": ("linux", "aarch64", "tar.gz"),
    "linux_x86_64": ("linux", "x64", "tar.gz"),
    "macos_aarch64": ("macos", "aarch64", "tar.gz"),
    "macos_x86_64": ("macos", "x64", "tar.gz"),
    "windows_x86_64": ("windows", "x64", "zip"),
}


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def archive(version, os, arch, archive_type):
    query = urllib.parse.urlencode({
        "java_version": version, "os": os, "arch": arch, "archive_type": archive_type, "java_package_type": "jdk",
        "javafx_bundled": "false", "crac_supported": "false", "release_status": "ga", "availability_types": "CA",
        "page_size": "100",
    })
    packages = [
        p for p in fetch(f"{API}?{query}")
        if "musl" not in p["name"] and ".".join(map(str, p["java_version"][:3])) == version
    ]
    if not packages:
        sys.exit(f"Azul publishes no Zulu {version} JDK for {os} {arch} ({archive_type})")
    newest = max(packages, key=lambda p: (p["java_version"], p["distro_version"]))
    return newest["name"], fetch(API + newest["package_uuid"])["sha256_hash"]


module_path, bazelrc_path = sys.argv[1], sys.argv[2]
module = open(module_path, encoding="utf-8").read()
version = re.search(r'^JDK = "([^"]+)"', module, re.M).group(1)

entry = re.compile(r'^(    "([a-z0-9_]+)": \[)"([^"]+)", "([0-9a-f]{64})"(\],)$', re.M)
# A pin already for this release (Zulu names it -jdk<version>.<build> or -jdk<version>-<os>) is kept, so repin.sh, which
# runs for every MODULE.bazel change Renovate makes, only asks Azul when JDK itself moved.
current = re.compile(rf"-jdk{re.escape(version)}[.-]")


def repin(match):
    if match.group(2) not in PLATFORMS:
        sys.exit(f"JDK_ARCHIVES has an unknown platform {match.group(2)}")
    name, sha256 = match.group(3), match.group(4)
    if not current.search(name):
        name, sha256 = archive(version, *PLATFORMS[match.group(2)])
    return f'{match.group(1)}"{name}", "{sha256}"{match.group(5)}'


updated, count = entry.subn(repin, module)
if count != len(PLATFORMS):
    sys.exit(f"found {count} JDK_ARCHIVES entries in {module_path}, expected {len(PLATFORMS)}")
if updated != module:
    open(module_path, "w", encoding="utf-8").write(updated)
    print(f"Re-pinned the Zulu {version} archives")

bazelrc = open(bazelrc_path, encoding="utf-8").read()
feature = version.split(".")[0]
updated = re.sub(r"(java_runtime_version=remotejdk_)\d+", r"\g<1>" + feature, bazelrc)
if updated != bazelrc:
    open(bazelrc_path, "w", encoding="utf-8").write(updated)
    print(f"Set .bazelrc's runtime to JDK {feature}")
