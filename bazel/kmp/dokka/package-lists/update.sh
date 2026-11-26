#!/usr/bin/env bash
# Re-downloads the external documentation's package lists the KDoc links against (dokka.bzl's _EXTERNAL_LINKS).
# Dokka runs offline, so the build never fetches them: refresh them here when a link's documentation moves on
# (a new JDK release's docs, new Kotlin or AndroidX packages). Run from the repository root.
set -euo pipefail
dir=bazel/kmp/dokka/package-lists
fetch() { curl --fail --silent --show-error --location --retry 3 --output "$dir/$1" "$2"; }
fetch java.element-list https://docs.oracle.com/en/java/javase/25/docs/api/element-list
fetch kotlin.package-list https://kotlinlang.org/api/core/package-list
fetch android.package-list https://developer.android.com/reference/kotlin/package-list
fetch androidx.package-list https://developer.android.com/reference/kotlin/androidx/package-list
