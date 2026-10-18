package kmp

import (
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"os"
	"path/filepath"
	"sort"
	"strings"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/rule"
)

// Directives (`# gazelle:<name> <value>` in a BUILD file, inherited by subdirectories):
const (
	// kmp_generate true|false: whether this directory's module gets generated targets. Off by default until
	// every module builds with Bazel (#1008).
	generateDirective = "kmp_generate"
	// kmp_targets android linuxX64 linuxArm64 …: the module's targets besides the JVM: `android`
	// (moneymanager.android-convention) and its Kotlin/Native targets (moneymanager.native-convention).
	targetsDirective = "kmp_targets"
	// kmp_plugins name …: compiler plugins to apply besides the ones inferred from the sources.
	pluginsDirective = "kmp_plugins"
	// kmp_export label [sourceSet]: a Gradle `api` dependency of the source set (default commonMain). It is
	// always a dependency, import or not, and goes to `exports` (repeatable).
	exportDirective = "kmp_export"
	// kmp_sqldelight_package com.example.sql: the package of the module's SQLDelight database.
	sqldelightPackageDirective = "kmp_sqldelight_package"
	// kmp_sqldelight_dep label: an upstream database (Gradle's `dependency(project)`), repeatable.
	sqldelightDepDirective = "kmp_sqldelight_dep"
	// kmp_lock_file path: the maven_kmp lockfile, for resolving library imports.
	lockFileDirective = "kmp_lock_file"
	// kmp_keep_dep label [sourceSet]: a dependency no import explains (a Metro contributor the graph merges, a
	// runtime-only library), added to the source set's deps (default commonMain; commonTest for the tests).
	// Repeatable; `kmp_export` still makes it an export.
	keepDepDirective = "kmp_keep_dep"
	// kmp_generated_srcs label …: generated sources (a kotlin_constants target) that join commonMain, as
	// Gradle's `kotlin.srcDir(task)` does.
	generatedSrcsDirective = "kmp_generated_srcs"
	// kmp_cinterops label …: kmp_cinterop targets of the main compilation (KGP's `cinterops.create`). A module
	// with cinterops but no Kotlin of its own still gets a kmp_library.
	cinteropsDirective = "kmp_cinterops"
	// kmp_test_data label …: files the module's tests read at run time (a test that scans source files).
	testDataDirective = "kmp_test_data"
	// kmp_test_size small|medium|large|enormous: the kmp_test size, for a suite slower than the default allows.
	testSizeDirective = "kmp_test_size"
	// kmp_test_jvm_flags flag …: JVM flags for the JVM tests, Gradle's `systemProperty`s (headless Compose
	// Desktop: -Djava.awt.headless=true -Dskiko.test.harness=true).
	testJvmFlagsDirective = "kmp_test_jvm_flags"
	// kmp_test_flaky true: retry failing JVM tests, as the Compose modules' Gradle test-retry plugin does.
	testFlakyDirective = "kmp_test_flaky"
	// kmp_main_platform android: what a non-KMP module's `src/main` compiles for: the JVM (`kotlin("jvm")`, the
	// default) or Android (`com.android.application`: the module then has no JVM compile).
	mainPlatformDirective = "kmp_main_platform"
	// kmp_android_host_tests false: an Android module whose tests don't run as host tests (Gradle's
	// `testAndroidHostTest` disabled: the Compose UI feature convention, tests that need a device).
	androidHostTestsDirective = "kmp_android_host_tests"
	// kmp_test_jvm_tags tag …: Bazel tags on the JVM tests (`no-remote-exec`: Skiko needs the host's libGL,
	// which BuildBuddy's executor image doesn't have).
	testJvmTagsDirective = "kmp_test_jvm_tags"
	// kmp_visibility label …: who may depend on the module (default public). `//app/model/repository/write`
	// lists the only modules that may take a write repository (CLAUDE.md, "Database Writes").
	visibilityDirective = "kmp_visibility"
	// kmp_forbid_deps label …: modules the module and its tests must never depend on, even transitively (the
	// pure importers: no db, DI or UI). Checked per platform by `bazel build`.
	forbidDepsDirective = "kmp_forbid_deps"
	// kmp_forbid_reference regex reason…: main sources must not match regex outside comments; the reason is
	// the error message. Checked by `bazel build`.
	forbidReferenceDirective = "kmp_forbid_reference"
	// kmp_allow_reference path …: module-relative files exempt from kmp_forbid_reference (exact paths).
	allowReferenceDirective = "kmp_allow_reference"
)

const androidTarget = "android"

// keepDep is one kmp_keep_dep or kmp_export: the label, the platform whose deps it joins, and whether it's
// the tests'.
type keepDep struct {
	label, platform string
	test            bool
}

type kmpConfig struct {
	generate          bool
	android           bool
	targets           []string
	plugins           []string
	exports           []keepDep
	sqldelightPackage string
	sqldelightDeps    []string
	keepDeps          []keepDep
	generatedSrcs     []string
	cinterops         []string
	testData          []string
	testSize          string
	testJvmFlags      []string
	testFlaky         bool
	testJvmTags       []string
	noHostTests       bool
	androidMain       bool // src/main is the Android leaf (kmp_main_platform android)
	visibility        []string
	forbidDeps        []string
	forbidReference   string
	referenceReason   string
	allowReference    []string
	lockFile          string
	maven             *mavenIndex
}

func (c *kmpConfig) clone() *kmpConfig {
	out := *c
	out.targets = append([]string(nil), c.targets...)
	out.plugins = append([]string(nil), c.plugins...)
	// Per-module settings don't carry into subdirectories.
	out.exports = nil
	out.sqldelightPackage = ""
	out.sqldelightDeps = nil
	out.keepDeps = nil
	out.generatedSrcs = nil
	out.cinterops = nil
	out.testData = nil
	out.testSize = ""
	out.testJvmFlags = nil
	out.testFlaky = false
	out.testJvmTags = nil
	out.noHostTests = false
	out.androidMain = false
	out.visibility = nil
	out.forbidDeps = nil
	out.forbidReference = ""
	out.referenceReason = ""
	out.allowReference = nil
	return &out
}

func getConfig(c *config.Config) *kmpConfig {
	return c.Exts[languageName].(*kmpConfig)
}

func (*kmpLang) RegisterFlags(fs *flag.FlagSet, cmd string, c *config.Config) {
	c.Exts[languageName] = &kmpConfig{lockFile: "bazel/kmp/deps/maven_kmp.lock.json"}
}

func (*kmpLang) CheckFlags(fs *flag.FlagSet, c *config.Config) error { return nil }

func (*kmpLang) KnownDirectives() []string {
	return []string{generateDirective, targetsDirective, pluginsDirective, exportDirective, sqldelightPackageDirective, sqldelightDepDirective, lockFileDirective, keepDepDirective, generatedSrcsDirective, cinteropsDirective, testDataDirective, testSizeDirective, testJvmFlagsDirective, testFlakyDirective, testJvmTagsDirective, androidHostTestsDirective, mainPlatformDirective, visibilityDirective, forbidDepsDirective, forbidReferenceDirective, allowReferenceDirective}
}

func (*kmpLang) Configure(c *config.Config, rel string, f *rule.File) {
	kc := getConfig(c).clone()
	c.Exts[languageName] = kc
	if f != nil {
		for _, d := range f.Directives {
			switch d.Key {
			case generateDirective:
				kc.generate = d.Value == "true"
			case targetsDirective:
				kc.android, kc.targets = false, nil
				for _, t := range strings.Fields(d.Value) {
					if t == androidTarget {
						kc.android = true
					} else {
						kc.targets = append(kc.targets, t)
					}
				}
			case pluginsDirective:
				kc.plugins = strings.Fields(d.Value)
			case exportDirective:
				if dep, err := parseKeepDep(d.Key, d.Value); err != nil {
					log.Printf("%s: %v", rel, err)
				} else {
					kc.exports = append(kc.exports, dep)
				}
			case sqldelightPackageDirective:
				kc.sqldelightPackage = d.Value
			case sqldelightDepDirective:
				kc.sqldelightDeps = append(kc.sqldelightDeps, d.Value)
			case testDataDirective:
				kc.testData = append(kc.testData, strings.Fields(d.Value)...)
			case testSizeDirective:
				kc.testSize = strings.TrimSpace(d.Value)
			case testJvmFlagsDirective:
				kc.testJvmFlags = append(kc.testJvmFlags, strings.Fields(d.Value)...)
			case testFlakyDirective:
				kc.testFlaky = strings.TrimSpace(d.Value) == "true"
			case testJvmTagsDirective:
				kc.testJvmTags = append(kc.testJvmTags, strings.Fields(d.Value)...)
			case mainPlatformDirective:
				switch v := strings.TrimSpace(d.Value); v {
				case "android":
					kc.androidMain = true
				case "jvm":
					kc.androidMain = false
				default:
					log.Printf("%s: %s wants jvm or android, got %q", rel, mainPlatformDirective, v)
				}
			case androidHostTestsDirective:
				kc.noHostTests = strings.TrimSpace(d.Value) == "false"
			case cinteropsDirective:
				kc.cinterops = append(kc.cinterops, strings.Fields(d.Value)...)
			case generatedSrcsDirective:
				kc.generatedSrcs = append(kc.generatedSrcs, strings.Fields(d.Value)...)
			case visibilityDirective:
				kc.visibility = append(kc.visibility, strings.Fields(d.Value)...)
			case forbidDepsDirective:
				kc.forbidDeps = append(kc.forbidDeps, strings.Fields(d.Value)...)
			case forbidReferenceDirective:
				regex, reason, _ := strings.Cut(strings.TrimSpace(d.Value), " ")
				kc.forbidReference = regex
				kc.referenceReason = strings.TrimSpace(reason)
			case allowReferenceDirective:
				kc.allowReference = append(kc.allowReference, strings.Fields(d.Value)...)
			case lockFileDirective:
				kc.lockFile = d.Value
			case keepDepDirective:
				if dep, err := parseKeepDep(d.Key, d.Value); err != nil {
					log.Printf("%s: %v", rel, err)
				} else {
					kc.keepDeps = append(kc.keepDeps, dep)
				}
			}
		}
	}
	if kc.maven == nil {
		kc.maven = loadMavenIndex(filepath.Join(c.RepoRoot, kc.lockFile))
	}
}

// mavenIndex maps a package to the @maven_kmp libraries that declare it.
type mavenIndex struct {
	byPackage map[string][]mavenLibrary
	exports   map[string][]string // library → the libraries its compile classpath (Gradle api) brings
	same      map[string][]string // library → those that are the same file on some platform (a root module and
	// the variant it points at: runtime is runtime-desktop's jar on the JVM)
	platforms map[string]map[string]bool // library → the lockfile platforms it has a variant for
}

type mavenLibrary struct {
	name     string // target name in @maven_kmp, without the platform suffix
	packages int    // how many packages it declares: fewer means more specific
	catalog  bool   // a catalog library, which Gradle builds name, rather than a module one brings
}

func loadMavenIndex(path string) *mavenIndex {
	idx := &mavenIndex{byPackage: map[string][]mavenLibrary{}, exports: map[string][]string{}, same: map[string][]string{}, platforms: map[string]map[string]bool{}}
	data, err := os.ReadFile(path)
	if err != nil {
		log.Printf("kmp: no maven_kmp lockfile at %s: %v", path, err)
		return idx
	}
	var lock struct {
		Inputs struct {
			Libraries map[string]string `json:"libraries"` // alias → group:artifact:version
		} `json:"inputs"`
		Names     map[string]string   `json:"names"`
		Packages  map[string][]string `json:"packages"`
		Artifacts map[string]map[string]struct {
			Deps  []string `json:"deps"`
			Files []struct {
				Name string `json:"name"`
			} `json:"files"`
		} `json:"artifacts"`
	}
	if err := json.Unmarshal(data, &lock); err != nil {
		log.Printf("kmp: %s: %v", path, err)
		return idx
	}
	catalog := map[string]bool{}
	for _, coordinates := range lock.Inputs.Libraries {
		group, rest, _ := strings.Cut(coordinates, ":")
		artifact, _, _ := strings.Cut(rest, ":")
		catalog[group+":"+artifact] = true
	}
	for module, pkgs := range lock.Packages {
		lib := mavenLibrary{name: lock.Names[module], packages: len(pkgs), catalog: catalog[module]}
		for _, p := range pkgs {
			idx.byPackage[p] = append(idx.byPackage[p], lib)
		}
	}
	byFile := map[string][]string{}
	for module, platforms := range lock.Artifacts {
		seen := map[string]bool{}
		name := lock.Names[module]
		if idx.platforms[name] == nil {
			idx.platforms[name] = map[string]bool{}
		}
		for platform, artifact := range platforms {
			idx.platforms[name][platform] = true
			for _, f := range artifact.Files {
				key := platform + "/" + f.Name
				byFile[key] = append(byFile[key], lock.Names[module])
			}
			for _, dep := range artifact.Deps {
				if name, ok := lock.Names[dep]; ok && !seen[name] {
					seen[name] = true
					idx.exports[lock.Names[module]] = append(idx.exports[lock.Names[module]], name)
				}
			}
		}
	}
	for _, libs := range byFile {
		for _, a := range libs {
			for _, b := range libs {
				if a != b {
					idx.same[a] = append(idx.same[a], b)
				}
			}
		}
	}
	for p, libs := range idx.byPackage {
		// A tool's fat jar (SQLDelight's compiler-env) repackages common packages; the library that declares
		// the fewest packages is the one an import means.
		sort.Slice(libs, func(i, j int) bool {
			if libs[i].packages != libs[j].packages {
				return libs[i].packages < libs[j].packages
			}
			return libs[i].name < libs[j].name
		})
		idx.byPackage[p] = libs
	}
	return idx
}

// find picks the library for a package, "" when no new one is needed. A package several libraries declare
// (androidx.compose.runtime is in runtime, runtime-desktop and runtime-annotation) is whichever one the module
// already names in a directive (`named`, as labels), or nothing when those already bring every one of them, as
// Mosaic's api brings the Compose runtime; then one whose api brings the rest; only then the most specific. (A tool's fat jar that repackages
// kotlinx.coroutines is never brought, so coroutines-core stays a dependency of its own.)
func (m *mavenIndex) find(pkg string, named map[string]bool, on []string) (string, bool) {
	var libs []mavenLibrary
	for _, lib := range m.byPackage[pkg] {
		if m.has(lib.name, on) {
			libs = append(libs, lib)
		}
	}
	if len(libs) == 0 {
		return "", false
	}
	if len(libs) > 1 {
		for _, lib := range libs {
			if named[mavenPrefix+lib.name] {
				return lib.name, true
			}
		}
		// Gradle builds name catalog libraries: on Android androidx's foundation, not the JetBrains module whose
		// Android variant it is.
		var fromCatalog []mavenLibrary
		for _, lib := range libs {
			if lib.catalog {
				fromCatalog = append(fromCatalog, lib)
			}
		}
		if len(fromCatalog) > 0 {
			libs = fromCatalog
		}
		reached := m.reached(named)
		all := true
		for _, lib := range libs {
			all = all && reached[lib.name]
		}
		if all {
			return "", true
		}
		// One that brings the rest covers whatever the import is (the runtime brings its annotations).
		for _, lib := range libs {
			brings := m.reached(map[string]bool{mavenPrefix + lib.name: true})
			covers := true
			for _, other := range libs {
				covers = covers && (other.name == lib.name || brings[other.name])
			}
			if covers {
				return lib.name, true
			}
		}
	}
	return libs[0].name, true
}

const mavenPrefix = "@maven_kmp//:"

// has is whether a library has a variant for each lockfile platform. One the lockfile doesn't list (one of
// //bazel/kmp/deps') is everywhere.
func (m *mavenIndex) has(lib string, platforms []string) bool {
	have, ok := m.platforms[lib]
	if !ok {
		return true
	}
	for _, p := range platforms {
		if !have[p] {
			return false
		}
	}
	return true
}

// reached is every library the named ones bring through their exports, transitively.
func (m *mavenIndex) reached(named map[string]bool) map[string]bool {
	out := map[string]bool{}
	var queue []string
	for l := range named {
		if name, ok := strings.CutPrefix(l, mavenPrefix); ok {
			queue = append(queue, name)
		}
	}
	for len(queue) > 0 {
		name := queue[0]
		queue = queue[1:]
		for _, dep := range append(append([]string(nil), m.exports[name]...), m.same[name]...) {
			if !out[dep] {
				out[dep] = true
				queue = append(queue, dep)
			}
		}
	}
	return out
}

// parseKeepDep reads a directive's `label [sourceSet]`.
func parseKeepDep(directive, value string) (keepDep, error) {
	fields := strings.Fields(value)
	if len(fields) == 0 || len(fields) > 2 {
		return keepDep{}, fmt.Errorf("%s wants `label [sourceSet]`, got %q", directive, value)
	}
	setName := "commonMain"
	if len(fields) == 2 {
		setName = fields[1]
	}
	for _, sets := range [][]sourceSet{mainSets, testSets} {
		for _, set := range sets {
			if set.dir == setName {
				return keepDep{label: fields[0], platform: set.platform, test: strings.HasSuffix(setName, "Test")}, nil
			}
		}
	}
	return keepDep{}, fmt.Errorf("%s: unknown source set %s", directive, setName)
}
