#!/usr/bin/env python3
"""GitHub's dependency-graph snapshot from the maven_kmp lockfiles (#1028).

Gradle's dependency-submission action read Gradle's resolved configurations; Bazel's resolution is the
lockfiles `bazel run @<hub>//:pin` writes, one per `maven_kmp.install`. Each becomes a manifest: every
library it resolved, at each version some platform resolved it to, direct when the install names it in
`libraries` and indirect otherwise, with its dependencies. The app's own libraries (`maven_kmp`) are
runtime dependencies; the build tools' (ktlint, detekt, ProGuard, …) are development ones. Usage, with
the job's `GITHUB_SHA`, `GITHUB_REF`, `GITHUB_RUN_ID`, `GITHUB_JOB` and `GITHUB_WORKFLOW` set:

    bazel/kmp/deps/dependency_snapshot.py bazel/kmp/deps/*.lock.json \
        | gh api repos/{owner}/{repo}/dependency-graph/snapshots --input -
"""

import datetime
import json
import os
import sys

RUNTIME = {"maven_kmp.lock.json"}


def purl(coordinate, version):
    group, artifact = coordinate.split(":")[:2]
    return "pkg:maven/%s/%s@%s" % (group, artifact, version)


def manifest(path):
    with open(path) as f:
        lock = json.load(f)
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
