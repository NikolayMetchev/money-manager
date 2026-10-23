"""Rewrites the SHA-256 of each Node.js archive MODULE.bazel's node.toolchain lists for its NODE version, from
nodejs.org's SHASUMS256.txt (repin.sh runs it after Renovate bumps NODE)."""

import re
import sys
import urllib.request

path = sys.argv[1]
module = open(path, encoding="utf-8").read()
version = re.search(r'^NODE = "([^"]+)"', module, re.M).group(1)
with urllib.request.urlopen(f"https://nodejs.org/dist/v{version}/SHASUMS256.txt") as response:
    sums = {name: sha for sha, name in (line.split() for line in response.read().decode().splitlines())}

# One entry per platform: ["node-v" + NODE + "<suffix>", "node-v" + NODE + "<prefix suffix>", "<sha256>"]
entry = re.compile(r'(\["node-v" \+ NODE \+ "([^"]+)", "node-v" \+ NODE \+ "[^"]+", ")([0-9a-f]{64})("\])')


def repin(match):
    archive = f"node-v{version}{match.group(2)}"
    if archive not in sums:
        sys.exit(f"{archive} isn't in Node {version}'s SHASUMS256.txt")
    return match.group(1) + sums[archive] + match.group(4)


updated, count = entry.subn(repin, module)
if count == 0:
    sys.exit("no Node.js archives found in " + path)
if updated != module:
    open(path, "w", encoding="utf-8").write(updated)
    print(f"Re-pinned {count} Node.js {version} archives")
