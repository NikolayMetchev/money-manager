"""Reads Kotlin/Native's `konan/konan.properties`: which dependencies konan needs for a host and target.

konan downloads these lazily into `~/.konan/dependencies` (LLVM, the target's GCC toolchain and sysroot,
libffi, …). We download them up front instead, sha256-pinned, so a build never reaches outside Bazel (#998).
The selection mirrors KonanPropertiesLoader: the host's LLVM and libffi, plus each target's `dependencies`,
`targetToolchain`, `targetSysRoot` and emulator, keyed `<host>-<target>` when cross-compiling.
"""

# konan's own names for the hosts and targets we build. Apple targets only build on macOS (they need
# Xcode); cross-compiling to linux_arm64 and mingw_x64 works from every desktop host.
HOST_TARGETS = {
    "linux_x64": ["linux_x64", "linux_arm64", "mingw_x64"],
    "macos_arm64": ["linux_x64", "linux_arm64", "macos_arm64", "mingw_x64"],
    "mingw_x64": ["linux_x64", "linux_arm64", "mingw_x64"],
}

# Targets a host compiles klibs for but can't cinterop, compile C for or link (#1089), each → the host whose
# distribution ships its platform klibs. konan compiles a macos_arm64 klib on Linux given those klibs; the
# SDK that cinterop, clang and the linker need is `remote:internal`. So CI's macOS job sends its klib
# compiles to BuildBuddy's Linux executors and keeps the rest on the Mac.
KLIB_ONLY_TARGETS = {
    "linux_x64": {"macos_arm64": "macos_arm64"},
}

# The platform klibs our libraries depend on, per target konan caches (`cacheableTargets` in konan.properties):
# every library a cache is built from needs its dependencies cached first, platform klibs included, so these get
# caches of their own (the repository adds what they depend on). konan names one that's missing ("X is going to
# be cached, but its dependency isn't"): add it here. The other ~170 Apple frameworks are left out, as nothing
# uses them and each is an action.
CACHED_PLATFORM_LIBS = {
    "linux_x64": ["builtin", "iconv", "linux", "posix", "zlib"],
    "macos_arm64": [
        "CFCGTypes",
        "CommonCrypto",
        "CoreFoundation",
        "CoreFoundationBase",
        "CoreServices",
        "DiskArbitration",
        "Foundation",
        "IOKit",
        "Security",
        "darwin",
        "libkern",
        "osx",
        "posix",
    ],
}

# The exec constraints of each host, for the actions that must run on it (a target's caches).
HOST_CONSTRAINTS = {
    "linux_x64": ["@platforms//os:linux", "@platforms//cpu:x86_64"],
    "macos_arm64": ["@platforms//os:macos", "@platforms//cpu:aarch64"],
    "mingw_x64": ["@platforms//os:windows", "@platforms//cpu:x86_64"],
}

DEPENDENCIES_URL = "https://download.jetbrains.com/kotlin/native"

def parse_properties(text):
    """Parses a Java properties file (`key = value`, `#` comments, `\\` line continuations) into a dict."""
    props = {}
    pending = ""
    for raw in text.splitlines():
        line = raw.strip()
        if not pending and (not line or line.startswith("#") or line.startswith("!")):
            continue
        if line.endswith("\\"):
            pending += line[:-1] + " "
            continue
        line = pending + line
        pending = ""
        sep = len(line)
        for c in ["=", ":"]:
            i = line.find(c)
            if i >= 0 and i < sep:
                sep = i
        if sep == len(line):
            continue
        props[line[:sep].strip()] = line[sep + 1:].strip()
    return props

def _is_name_char(c):
    return c.isalnum() or c in "_.-"

def _expand_once(props, value):
    out = ""
    i = 0
    n = len(value)
    for _ in range(n + 1):
        if i >= n:
            break
        if value[i] != "$":
            out += value[i]
            i += 1
            continue
        j = i + 1
        for _ in range(n):
            if j >= n or not _is_name_char(value[j]):
                break
            j += 1
        name = value[i + 1:j].rstrip(".")
        if name not in props:
            fail("konan.properties: unknown property $%s" % name)
        out += props[name]
        i += 1 + len(name)
    return out

def expand(props, value):
    """Expands `$name` references (konan's own substitution syntax) in a property value."""
    for _ in range(10):
        if "$" not in value:
            return value
        value = _expand_once(props, value)
    fail("konan.properties: substitution too deep in %r" % value)

def _lookup(props, key, host, target):
    """konan's host-target lookup: `<key>.<host>-<target>` when cross-compiling, else `<key>.<target>`."""
    if host == target:
        return props.get("%s.%s" % (key, target))
    return props.get("%s.%s-%s" % (key, host, target))

def _first_segment(path):
    return path.split("/")[0]

def dependencies(props, host, targets):
    """The dependency names konan needs on `host` for `targets`, in a stable order, without duplicates.

    Dependencies served from JetBrains' internal server (`remote:internal`: the Xcode toolchain and
    sysroots) are left out: on macOS konan takes those from the installed Xcode.
    """
    names = []
    candidates = [
        props.get("llvmHome." + host),
        props.get("libffiDir." + host),
    ]
    for target in targets:
        deps = _lookup(props, "dependencies", host, target)
        if deps:
            candidates.extend([w for w in expand(props, deps).replace("\t", " ").split(" ") if w])
        for key in ["targetToolchain", "emulatorDependency"]:
            value = _lookup(props, key, host, target)
            if value:
                candidates.append(value)
        candidates.append(props.get("targetSysRoot." + target))
    for candidate in candidates:
        if not candidate:
            continue
        name = _first_segment(expand(props, candidate))
        if name in names or "remote:internal" in props.get(name + ".default", ""):
            continue
        names.append(name)
    return sorted(names)

def dependency_url(props, name, archive_ext):
    """Where konan would download `name` from (`<name>.default = remote:public:<path>`, else the root)."""
    location = props.get(name + ".default", "")
    prefix = "remote:public:"
    if location.startswith(prefix):
        return "%s/%s/%s.%s" % (DEPENDENCIES_URL, location[len(prefix):].strip(), name, archive_ext)
    return "%s/%s.%s" % (DEPENDENCIES_URL, name, archive_ext)
