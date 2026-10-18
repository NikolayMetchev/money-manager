#!/usr/bin/env python3
"""The Pages site's module graph (webpage/modules-graph.md) from `bazel query` (#1027).

Reads `bazel query --output=streamed_jsonproto` over the modules on stdin and writes the Mermaid graph the
module-graph Gradle plugin wrote, which webpage/modules.html parses: `:from -- <compilation> --> :to`, one
edge per module dependency of each compilation (`jvmMain`, `androidTest`, `linuxX64Main`, …). A module's
compilations are its kmp_library/kmp_test targets (`<name>_<platform>`, `test_<platform>_lib`/`_bin`);
an edge is a dep or export on another module's target. Usage:

    bazel query '//app/... + //utils/... + //tools/... + //test/...' --output=streamed_jsonproto \
        | bazel/pages/module_graph.py > webpage/modules-graph.md
"""

import json
import re
import sys

PLATFORMS = ("jvm", "android", "linuxX64")
MAIN = re.compile(r"^(?P<name>.+)_(?P<platform>%s)$" % "|".join(PLATFORMS))
TEST = re.compile(r"^test_(?P<platform>%s)_(lib|bin)$" % "|".join(PLATFORMS))
# A module's own target for a platform: `//pkg:<name>_<platform>`, or its testonly `_for_tests` twin.
MODULE_TARGET = re.compile(r"^//(?P<pkg>[^:]+):(?P<name>[^:]+?)(_for_tests)?_(%s)$" % "|".join(PLATFORMS))


def gradle_path(package):
    return ":" + package.replace("/", ":")


def main():
    rules = {}
    for line in sys.stdin:
        target = json.loads(line)
        if target.get("type") != "RULE":
            continue
        rule = target["rule"]
        rules[rule["name"]] = {a["name"]: a for a in rule.get("attribute", [])}

    # A package is a module when its kmp_library's name is its last path segment (Gazelle names it so).
    modules = set()
    for label, attrs in rules.items():
        package, name = label[2:].split(":", 1)
        if attrs.get("generator_function", {}).get("stringValue") == "kmp_library" and MAIN.match(name):
            modules.add(package)

    platforms = {}  # (module, "Main"|"Test") -> its compilations' platforms
    found = {}  # (module, "Main"|"Test", dependency module) -> the platforms whose compilation has it
    for label, attrs in rules.items():
        package, name = label[2:].split(":", 1)
        if package not in modules:
            continue
        main = MAIN.match(name)
        test = TEST.match(name)
        if main and attrs.get("generator_function", {}).get("stringValue") == "kmp_library":
            kind, platform = "Main", main.group("platform")
        elif test:
            kind, platform = "Test", test.group("platform")
        else:
            continue
        platforms.setdefault((package, kind), set()).add(platform)
        for attr in ("deps", "exports"):
            for dep in attrs.get(attr, {}).get("stringListValue", []):
                m = MODULE_TARGET.match(dep)
                if m and m.group("pkg") in modules and m.group("pkg") != package:
                    found.setdefault((package, kind, m.group("pkg")), set()).add(platform)

    edges = set()
    for (package, kind, dependency), on in found.items():
        common = on == platforms[(package, kind)] and len(on) > 1
        for compilation in ["common" + kind] if common else [p + kind for p in on]:
            edges.add((gradle_path(package), compilation, gradle_path(dependency)))

    print("## Module dependency graph")
    print()
    print("```mermaid")
    print("%%{")
    print("  init: {")
    print("    'theme': 'neutral'")
    print("  }")
    print("}%%")
    print()
    print("graph LR")
    for source, compilation, target in sorted(edges):
        print("  %s -- %s --> %s" % (source, compilation, target))
    print("```")


if __name__ == "__main__":
    main()
