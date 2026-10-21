"""`sqlite_amalgamation` (#1012): the SQLite amalgamation utils/sqlite3 compiles for every native target, at the
release, year and SHA-256 MODULE.bazel's `sqlite_amalgamation.version` pins."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")

_BUILD = """\
exports_files(["sqlite3.c", "sqlite3.h"], visibility = ["//visibility:public"])
"""

def _impl(mctx):
    pins = [tag for mod in mctx.modules if mod.is_root for tag in mod.tags.version]
    if len(pins) != 1:
        fail("sqlite_amalgamation: the root module pins the release with exactly one sqlite_amalgamation.version(…)")
    pin = pins[0]
    http_archive(
        name = "sqlite_amalgamation",
        url = "https://www.sqlite.org/%s/sqlite-amalgamation-%s.zip" % (pin.year, pin.release),
        sha256 = pin.sha256,
        strip_prefix = "sqlite-amalgamation-" + pin.release,
        build_file_content = _BUILD,
    )
    return mctx.extension_metadata(reproducible = True)

sqlite_amalgamation = module_extension(
    implementation = _impl,
    tag_classes = {"version": tag_class(attrs = {
        "release": attr.string(mandatory = True, doc = "sqlite.org's download number: 3.54.0 is 3540000."),
        "sha256": attr.string(mandatory = True),
        "year": attr.string(mandatory = True, doc = "The year directory sqlite.org keeps the release under."),
    })},
)
