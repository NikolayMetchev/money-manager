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
	// kmp_targets linuxX64 linuxArm64 …: the module's Kotlin/Native targets (moneymanager.native-convention).
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
)

// keepDep is one kmp_keep_dep or kmp_export: the label, the platform whose deps it joins, and whether it's
// the tests'.
type keepDep struct {
	label, platform string
	test            bool
}

type kmpConfig struct {
	generate          bool
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
	return []string{generateDirective, targetsDirective, pluginsDirective, exportDirective, sqldelightPackageDirective, sqldelightDepDirective, lockFileDirective, keepDepDirective, generatedSrcsDirective, cinteropsDirective, testDataDirective, testSizeDirective}
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
				kc.targets = strings.Fields(d.Value)
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
			case cinteropsDirective:
				kc.cinterops = append(kc.cinterops, strings.Fields(d.Value)...)
			case generatedSrcsDirective:
				kc.generatedSrcs = append(kc.generatedSrcs, strings.Fields(d.Value)...)
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
}

type mavenLibrary struct {
	name     string // target name in @maven_kmp, without the platform suffix
	packages int    // how many packages it declares: fewer means more specific
}

func loadMavenIndex(path string) *mavenIndex {
	idx := &mavenIndex{byPackage: map[string][]mavenLibrary{}}
	data, err := os.ReadFile(path)
	if err != nil {
		log.Printf("kmp: no maven_kmp lockfile at %s: %v", path, err)
		return idx
	}
	var lock struct {
		Names    map[string]string   `json:"names"`
		Packages map[string][]string `json:"packages"`
	}
	if err := json.Unmarshal(data, &lock); err != nil {
		log.Printf("kmp: %s: %v", path, err)
		return idx
	}
	for module, pkgs := range lock.Packages {
		lib := mavenLibrary{name: lock.Names[module], packages: len(pkgs)}
		for _, p := range pkgs {
			idx.byPackage[p] = append(idx.byPackage[p], lib)
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

func (m *mavenIndex) find(pkg string) (string, bool) {
	libs := m.byPackage[pkg]
	if len(libs) == 0 {
		return "", false
	}
	return libs[0].name, true
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
