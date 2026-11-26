#!/usr/bin/env bash
# Re-downloads the external documentation's package lists the KDoc links against (dokka.bzl's _EXTERNAL_LINKS).
# Dokka runs offline, so the build never fetches them. CI's Auto-format PR job runs this on every pull request and
# pushes what changed; run it by hand from the repository root otherwise.
set -euo pipefail
dir=bazel/kmp/dokka/package-lists
# Each list lands only once it downloaded whole and isn't empty: CI commits whatever this leaves.
fetch() {
  local tmp
  tmp=$(mktemp)
  if curl --fail --silent --show-error --location --retry 3 --output "$tmp" "$2" && [ -s "$tmp" ]; then
    chmod 644 "$tmp"
    mv "$tmp" "$dir/$1"
  else
    rm -f "$tmp"
    return 1
  fi
}
fetch java.element-list https://docs.oracle.com/en/java/javase/25/docs/api/element-list
fetch kotlin.package-list https://kotlinlang.org/api/core/package-list
fetch android.package-list https://developer.android.com/reference/kotlin/package-list
fetch androidx.package-list https://developer.android.com/reference/kotlin/androidx/package-list
