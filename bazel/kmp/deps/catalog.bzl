"""Reads gradle/libs.versions.toml, the one place versions live while Gradle and Bazel build side by side."""

def _strip_comment(line):
    quoted = False
    for i in range(len(line)):
        c = line[i]
        if c == '"':
            quoted = not quoted
        elif c == "#" and not quoted:
            return line[:i].strip()
    return line.strip()

def catalog(text):
    """The catalog's [versions] and [libraries] (`module` + `version.ref`), as the resolver reads them."""
    versions = {}
    libraries = {}
    table = ""
    for raw in text.splitlines():
        line = _strip_comment(raw)
        if not line:
            continue
        if line.startswith("[") and line.endswith("]"):
            table = line[1:-1]
            continue
        key, sep, value = line.partition("=")
        if not sep:
            continue
        key = key.strip()
        value = value.strip()
        if table == "versions" and value.startswith('"'):
            versions[key] = value.strip('"')
        elif table == "libraries" and value.startswith("{"):
            fields = {}
            for field in value.strip("{} ").split(","):
                k, _, v = field.partition("=")
                fields[k.strip()] = v.strip().strip('"')
            libraries[key] = fields
    return versions, libraries
