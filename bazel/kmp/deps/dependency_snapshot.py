#!/usr/bin/env python3
"""GitHub's dependency-graph snapshot from the Maven lockfiles (#1028).

Gradle's dependency-submission action read Gradle's resolved configurations; Bazel's resolution is the
lockfiles `bazel run @<hub>//:pin` writes. Each becomes a manifest: every library it resolved, with its
dependencies. The app's own libraries (the `maven_kmp` lockfile, at each version some platform resolved them
to, direct when the install names it in `libraries`) are runtime dependencies. The build tools' (ktlint,
detekt, ProGuard, …: rules_jvm_external's `bazel/maven/<name>_install.json`, direct when its MODULE.bazel
`maven.install` lists it above the transitive pins) are development ones. Usage, with the job's
`GITHUB_SHA`, `GITHUB_REF`, `GITHUB_RUN_ID`, `GITHUB_JOB` and `GITHUB_WORKFLOW` set:

    bazel/kmp/deps/dependency_snapshot.py bazel/kmp/deps/maven_kmp.lock.json bazel/maven/*_install.json \
        | gh api repos/{owner}/{repo}/dependency-graph/snapshots --input -
"""

import datetime
import json
import os
import re
import sys

RUNTIME = {"maven_kmp.lock.json"}


def purl(coordinate, version):
    group, artifact = coordinate.split(":")[:2]
    return "pkg:maven/%s/%s@%s" % (group, artifact, version)


def maven_install_artifacts(name):
    """The coordinates MODULE.bazel's `maven.install(name = …)` asks for, without its transitive pins."""
    with open(os.path.join(os.path.dirname(__file__), "..", "..", "..", "MODULE.bazel")) as f:
        module = f.read()
    install = re.search(r'maven\.install\(\s*name = "%s",\s*artifacts = \[(.*?)\]' % re.escape(name), module, re.S)
    if not install:
        return set()
    listed = install.group(1).split("# Transitive versions")[0]
    return {":".join(c.split(":")[:2]) for c in re.findall(r'"([^"]+)"', listed)}


def maven_install_manifest(path, lock):
    """A rules_jvm_external lockfile: one version per library, its dependencies by coordinate."""
    dependencies = lock.get("dependencies", {})
    direct = maven_install_artifacts(os.path.basename(path)[: -len("_install.json")])
    version = {coordinate: artifact["version"] for coordinate, artifact in lock["artifacts"].items()}
    resolved = {}
    for coordinate in sorted(version):
        package = purl(coordinate, version[coordinate])
        resolved[package] = {
            "package_url": package,
            "relationship": "direct" if coordinate in direct else "indirect",
            "scope": "development",
            "dependencies": sorted(
                purl(d, version[d]) for d in dependencies.get(coordinate, []) if d in version
            ),
        }
    return {"name": path, "file": {"source_location": path}, "resolved": resolved}


def manifest(path):
    with open(path) as f:
        lock = json.load(f)
    if "__INPUT_ARTIFACTS_HASH" in lock:
        return maven_install_manifest(path, lock)
    direct = {":".join(c.split(":")[:2]) for c in lock["inputs"]["libraries"].values()}
    scope = "runtime" if os.path.basename(path) in RUNTIME else "development"
    resolved = {}
    for coordinate, platforms in lock["artifacts"].items():
        for platform, artifact in platforms.items():
            package = purl(coordinate, artifact["version"])
            entry = resolved.setdefault(package, {
                "package_url": package,
                "relationship": "direct" if coordinate in direct else "indirect",
                "scope": scope,
                "dependencies": set(),
            })
            for dependency in artifact.get("deps", []):
                # A dependency's version can differ per platform: take the one this platform resolved.
                candidates = lock["artifacts"].get(dependency, {})
                resolved_dependency = candidates.get(platform) or next(iter(candidates.values()), None)
                if resolved_dependency:
                    entry["dependencies"].add(purl(dependency, resolved_dependency["version"]))
    for entry in resolved.values():
        entry["dependencies"] = sorted(entry["dependencies"])
    return {
        "name": path,
        "file": {"source_location": path},
        "resolved": dict(sorted(resolved.items())),
    }


def main():
    snapshot = {
        "version": 0,
        "sha": os.environ["GITHUB_SHA"],
        "ref": os.environ["GITHUB_REF"],
        "job": {
            "correlator": "%s_%s" % (os.environ["GITHUB_WORKFLOW"], os.environ["GITHUB_JOB"]),
            "id": os.environ["GITHUB_RUN_ID"],
        },
        "detector": {
            "name": "money-manager maven_kmp",
            "version": "1",
            "url": "https://github.com/NikolayMetchev/money-manager/tree/main/bazel/kmp/deps",
        },
        "scanned": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "manifests": {path: manifest(path) for path in sorted(sys.argv[1:])},
    }
    json.dump(snapshot, sys.stdout, indent=1)


if __name__ == "__main__":
    main()
