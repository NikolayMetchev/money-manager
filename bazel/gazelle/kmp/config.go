package kmp

import (
	"encoding/json"
	"flag"
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
	// kmp_export label: a dependency that is Gradle `api`, so it goes to `exports` (repeatable).
	exportDirective = "kmp_export"
	// kmp_sqldelight_package com.example.sql: the package of the module's SQLDelight database.
	sqldelightPackageDirective = "kmp_sqldelight_package"
	// kmp_sqldelight_dep label: an upstream database (Gradle's `dependency(project)`), repeatable.
	sqldelightDepDirective = "kmp_sqldelight_dep"
	// kmp_lock_file path: the maven_kmp lockfile, for resolving library imports.
	lockFileDirective = "kmp_lock_file"
)

type kmpConfig struct {
	generate          bool
	targets           []string
	plugins           []string
	exports           map[string]bool
	sqldelightPackage string
	sqldelightDeps    []string
	lockFile          string
	maven             *mavenIndex
}

func (c *kmpConfig) clone() *kmpConfig {
	out := *c
	out.targets = append([]string(nil), c.targets...)
	out.plugins = append([]string(nil), c.plugins...)
	out.exports = map[string]bool{}
	for k := range c.exports {
		out.exports[k] = true
	}
	// Per-module settings don't carry into subdirectories.
	out.sqldelightPackage = ""
	out.sqldelightDeps = nil
	return &out
}

func getConfig(c *config.Config) *kmpConfig {
	return c.Exts[languageName].(*kmpConfig)
}

func (*kmpLang) RegisterFlags(fs *flag.FlagSet, cmd string, c *config.Config) {
	c.Exts[languageName] = &kmpConfig{exports: map[string]bool{}, lockFile: "bazel/kmp/deps/maven_kmp.lock.json"}
}

func (*kmpLang) CheckFlags(fs *flag.FlagSet, c *config.Config) error { return nil }

func (*kmpLang) KnownDirectives() []string {
	return []string{generateDirective, targetsDirective, pluginsDirective, exportDirective, sqldelightPackageDirective, sqldelightDepDirective, lockFileDirective}
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
				kc.exports[d.Value] = true
			case sqldelightPackageDirective:
				kc.sqldelightPackage = d.Value
			case sqldelightDepDirective:
				kc.sqldelightDeps = append(kc.sqldelightDeps, d.Value)
			case lockFileDirective:
				kc.lockFile = d.Value
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
