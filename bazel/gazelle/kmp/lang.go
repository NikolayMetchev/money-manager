// Package kmp is the Gazelle language extension for Kotlin Multiplatform modules (#1004): it generates every
// module's kmp_library, kmp_test and sqldelight_library from its `src/<sourceSet>` directories and the
// imports in them, so no BUILD file is maintained by hand.
//
// A directory with `src/<sourceSet>/kotlin` sources is one module, named after the directory. Its source
// sets decide the `*_srcs` attributes; imports decide `deps` per platform (commonMain's for every platform,
// jvmAndroidMain/jvmMain's for the JVM, nativeMain's for native, and linuxMain/appleMain/mingwMain's for that
// family's targets), each written once, at the highest source set that needs it. An import
// resolves to the module that declares its package (one package per module), else to the @maven_kmp
// library whose jar or klib declares it (the lockfile records each library's packages). Compiler plugins
// are inferred from what the sources use. What sources can't say comes from directives (config.go).
package kmp

import (
	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/rule"
)

const (
	languageName = "kmp"
	packagesKey  = "_kmp_packages"
)

type kmpLang struct{}

// NewLanguage is the entry point gazelle_binary calls.
func NewLanguage() language.Language { return &kmpLang{} }

func (*kmpLang) Name() string { return languageName }

var resolvedAttrs = map[string]bool{
	"deps":                true,
	"exports":             true,
	"jvm_android_deps":    true,
	"jvm_android_exports": true,
	"jvm_deps":            true,
	"jvm_exports":         true,
	"android_deps":        true,
	"android_exports":     true,
	"device_deps":         true,
	"native_deps":         true,
	"linux_deps":          true,
	"apple_deps":          true,
	"mingw_deps":          true,
}

// The `*_srcs` globs aren't mergeable: each is fixed for its source set, Gazelle can't merge every glob
// expression, and a source set's attribute is only ever added.
func mergeable(extra ...string) map[string]bool {
	out := map[string]bool{"android": true, "native_targets": true, "plugins": true}
	for _, k := range extra {
		out[k] = true
	}
	return out
}

func (*kmpLang) Kinds() map[string]rule.KindInfo {
	return map[string]rule.KindInfo{
		"kmp_library": {
			NonEmptyAttrs:  map[string]bool{"common_srcs": true, "sqldelight": true, "cinterops": true},
			MergeableAttrs: mergeable("sqldelight", "generated_srcs", "cinterops", "visibility", "forbidden_deps", "forbidden_reference", "forbidden_reference_reason", "forbidden_reference_allowed", "keep_deps"),
			ResolveAttrs:   resolvedAttrs,
		},
		"kmp_test": {
			NonEmptyAttrs:  map[string]bool{"associate": true},
			MergeableAttrs: mergeable("associate", "data", "size", "forbidden_deps", "keep_deps", "jvm_flags", "flaky", "device_manifest"),
			ResolveAttrs:   resolvedAttrs,
		},
		"sqldelight_library": {
			NonEmptyAttrs:  map[string]bool{"srcs": true},
			MergeableAttrs: map[string]bool{"package": true, "module_name": true, "deps": true},
		},
	}
}

func (*kmpLang) Loads() []rule.LoadInfo {
	return []rule.LoadInfo{
		{Name: "//bazel/kmp:defs.bzl", Symbols: []string{"kmp_library", "kmp_test"}},
		{Name: "//bazel/kmp/sqldelight:sqldelight.bzl", Symbols: []string{"sqldelight_library"}},
	}
}

// Fix drops each generated rule's `resources` before the merge, so the generated glob always replaces it (or
// it goes, with its dirs): Gazelle only merges string lists and selects, not globs.
func (*kmpLang) Fix(c *config.Config, f *rule.File) {
	if f == nil || !getConfig(c).generate {
		return
	}
	for _, r := range f.Rules {
		if (r.Kind() == "kmp_library" || r.Kind() == "kmp_test") && !r.ShouldKeep() {
			r.DelAttr("resources")
			r.DelAttr("device_resources")
			r.DelAttr("device_srcs")
		}
	}
}

var _ config.Configurer = (*kmpLang)(nil)
