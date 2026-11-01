"""Sets the bytecode version everything compiles for: MODULE.bazel's JDK, or the newest JVM target the Kotlin compiler
has if that is older (Kotlin 2.4.21 targets up to 26). Written to .bazelrc's java_language_versions, the Kotlin
toolchain's jvm_target and the Java toolchain's source and target versions, which move together (repin.sh runs it
after a JDK or Kotlin bump, so either one raises the bytecode as far as the other allows).

Usage: repin_bytecode.py <kotlinc home> (rules_kotlin's compiler repository: build.txt and lib/kotlin-compiler.jar).
"""

import re
import sys
import zipfile

kotlinc = sys.argv[1]
module = open("MODULE.bazel", encoding="utf-8").read()
jdk = int(re.search(r'^JDK = "(\d+)[^"]*"', module, re.M).group(1))
kotlin = re.search(r'^KOTLIN = "([^"]+)"', module, re.M).group(1)

compiler_version = open(f"{kotlinc}/build.txt", encoding="utf-8").read().strip()
if compiler_version != kotlin:
    sys.exit(f"rules_kotlin's compiler is {compiler_version}, MODULE.bazel's KOTLIN is {kotlin}")

# JvmTarget's constants are named after the targets: JVM_1_8, JVM_9, …, JVM_26.
enum = zipfile.ZipFile(f"{kotlinc}/lib/kotlin-compiler.jar").read("org/jetbrains/kotlin/config/JvmTarget.class")
kotlin_newest = max(int(n) for n in re.findall(rb"JVM_(\d+)(?![\d_])", enum))
target = str(min(jdk, kotlin_newest))

FILES = {
    ".bazelrc": [r"(java_language_version=)\d+"],
    "bazel/kmp/BUILD.bazel": [r'(jvm_target = ")\d+'],
    "bazel/kmp/java/BUILD.bazel": [r'(source_version = ")\d+', r'(target_version = ")\d+'],
}
changed = False
for path, patterns in FILES.items():
    text = open(path, encoding="utf-8").read()
    updated = text
    for pattern in patterns:
        updated, count = re.subn(pattern, r"\g<1>" + target, updated)
        if count == 0:
            sys.exit(f"{path} has no {pattern}")
    if updated != text:
        open(path, "w", encoding="utf-8").write(updated)
        changed = True
if changed:
    print(f"Compiling for Java {target} (JDK {jdk}, Kotlin {kotlin} targets up to {kotlin_newest})")
