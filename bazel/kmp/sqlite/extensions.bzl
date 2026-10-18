"""`sqlite_amalgamation` (#1012): the SQLite amalgamation utils/sqlite3 compiles for every native target, at the
release, year and SHA-256 that `sqlite-amalgamation-*` pins in libs.versions.toml."""

load("@bazel_tools//tools/build_defs/repo:http.bzl", "http_archive")
load("//bazel/kmp/deps:catalog.bzl", "catalog")

_BUILD = """\
exports_files(["sqlite3.c", "sqlite3.h"], visibility = ["//visibility:public"])
"""

def _impl(mctx):
    versions, _, _ = catalog(mctx.read(Label("//:libs.versions.toml")))
    release = versions["sqlite-amalgamation-release"]
    http_archive(
        name = "sqlite_amalgamation",
        url = "https://www.sqlite.org/%s/sqlite-amalgamation-%s.zip" % (versions["sqlite-amalgamation-year"], release),
        sha256 = versions["sqlite-amalgamation-sha256"],
        strip_prefix = "sqlite-amalgamation-" + release,
        build_file_content = _BUILD,
    )
    return mctx.extension_metadata(reproducible = True)

sqlite_amalgamation = module_extension(implementation = _impl)
