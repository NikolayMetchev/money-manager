"""The default desktop Google OAuth client, for `kotlin_constants(obfuscated = …)` (#1011).

The values come from `GOOGLE_OAUTH_CLIENT_ID` / `GOOGLE_OAUTH_CLIENT_SECRET` (CI's secrets), else the
gitignored `secrets/google-oauth.properties` (`clientId`, `clientSecret`), else empty, so a build without them
still compiles and the feature reports "not configured". They're XOR-obfuscated here, at repository time, as
build-logic's `xorObfuscate` does: the plaintext never becomes an action input or a cache key, and the
`GOCSPX-…` literal never lands in an output for secret scanners to match. A speed bump, not protection: the
real safeguards are the Cloud project's quota cap and rotating the client.
"""

# Must match build-logic's XOR_BASE and the generator's.
_XOR_BASE = 0x3B
_HEX = "0123456789abcdef"

# Starlark has no ord(): the printable ASCII an OAuth client id and secret are made of.
_CODES = {chr: 0x20 + i for i, chr in enumerate(" !\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~".elems())}

def xor_obfuscate(value):
    out = []
    for index, chr in enumerate(value.elems()):
        if chr not in _CODES:
            fail("google_oauth: only printable ASCII is supported, got %r" % chr)
        code = _CODES[chr] ^ ((_XOR_BASE + index) & 0xFFFF)
        out.append("".join([_HEX[(code >> shift) & 0xF] for shift in (12, 8, 4, 0)]))
    return "".join(out)

def _properties(rctx):
    path = rctx.workspace_root.get_child("secrets").get_child("google-oauth.properties")
    rctx.watch(path)
    values = {}
    if not path.exists:
        return values
    for line in rctx.read(path).splitlines():
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("!"):
            continue
        sep = [i for i, c in enumerate(line.elems()) if c in "=:"]
        if sep:
            values[line[:sep[0]].strip()] = line[sep[0] + 1:].strip()
    return values

def _google_oauth_repo_impl(rctx):
    properties = _properties(rctx)
    for name, env, key in [
        ("client_id", "GOOGLE_OAUTH_CLIENT_ID", "clientId"),
        ("client_secret", "GOOGLE_OAUTH_CLIENT_SECRET", "clientSecret"),
    ]:
        # Trimmed before the emptiness check: a CI secret's trailing newline would embed an invalid credential.
        value = (rctx.getenv(env) or "").strip() or properties.get(key, "")
        rctx.file(name + ".hex", xor_obfuscate(value), executable = False)
    rctx.file("BUILD.bazel", 'exports_files(["client_id.hex", "client_secret.hex"])\n', executable = False)

_google_oauth_repo = repository_rule(
    implementation = _google_oauth_repo_impl,
    environ = ["GOOGLE_OAUTH_CLIENT_ID", "GOOGLE_OAUTH_CLIENT_SECRET"],
    local = True,
)

def _google_oauth_impl(mctx):
    _google_oauth_repo(name = "google_oauth")
    return mctx.extension_metadata(reproducible = False)

google_oauth = module_extension(implementation = _google_oauth_impl)
