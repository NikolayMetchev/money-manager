"""Rewrites the SHA-256 of each pdfium archive MODULE.bazel downloads for its PDFIUM build, by downloading them
(bblanchon/pdfium-binaries publishes no checksum file). repin.sh runs it after Renovate bumps PDFIUM."""

import hashlib
import re
import sys
import urllib.request

path = sys.argv[1]
module = open(path, encoding="utf-8").read()
build = re.search(r'^PDFIUM = "([^"]+)"', module, re.M).group(1)

# One http_archive per platform: its sha256 line, then its url line naming the archive.
entry = re.compile(r'(sha256 = ")([0-9a-f]{64})(",\n    url = "https://github\.com/bblanchon/pdfium-binaries/[^"]+" \+ PDFIUM \+ "/(pdfium-[^"]+\.tgz)")')


def repin(match):
    url = f"https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F{build}/{match.group(4)}"
    with urllib.request.urlopen(url) as response:
        sha = hashlib.sha256(response.read()).hexdigest()
    return match.group(1) + sha + match.group(3)


updated, count = entry.subn(repin, module)
if count == 0:
    sys.exit("no pdfium archives found in " + path)
if updated != module:
    open(path, "w", encoding="utf-8").write(updated)
    print(f"Re-pinned {count} pdfium {build} archives")
