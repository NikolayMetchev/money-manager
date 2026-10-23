"""Sets MODULE.bazel's SKIKO to the Skiko release its COMPOSE_PLUGIN was built against, read from Compose's
ui-graphics Gradle module (repin.sh runs it after Renovate bumps Compose). Compose's klibs link only against that
Skiko, so Skiko isn't a Renovate dependency of its own."""

import json
import re
import sys
import urllib.request

path = sys.argv[1]
module = open(path, encoding="utf-8").read()
compose = re.search(r'^COMPOSE_PLUGIN = "([^"]+)"', module, re.M).group(1)
url = f"https://repo1.maven.org/maven2/org/jetbrains/compose/ui/ui-graphics/{compose}/ui-graphics-{compose}.module"
with urllib.request.urlopen(url) as response:
    gradle_module = json.load(response)
skiko = {
    dependency["version"]["requires"]
    for variant in gradle_module["variants"]
    for dependency in variant.get("dependencies", [])
    if dependency["group"] == "org.jetbrains.skiko" and dependency["module"] == "skiko"
}
if len(skiko) != 1:
    sys.exit(f"Compose {compose}'s ui-graphics names Skiko {sorted(skiko)}, want one release")
version = skiko.pop()

updated, count = re.subn(r'^(SKIKO = ")[^"]+(")', rf"\g<1>{version}\g<2>", module, flags=re.M)
if count != 1:
    sys.exit("no SKIKO constant in " + path)
if updated != module:
    open(path, "w", encoding="utf-8").write(updated)
    print(f"Set Skiko to {version}, Compose {compose}'s")
