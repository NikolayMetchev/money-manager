// The KMP Maven resolver (#1000): resolves catalog libraries for every platform from Gradle Module Metadata
// and writes the lockfile the `maven_kmp` module extension reads.
//
// rules_jvm_external reads POMs, but a Kotlin Multiplatform library's root POM names no platform's files:
// its `.module` file points each platform at another module (`-jvm` jar, `-linuxx64` klib, …). This follows
// those variants the way Gradle does, per platform, and records every file with its sha256.
//
// Run it through `bazel run @maven_kmp//:pin`, which passes MODULE.bazel's `maven_kmp.install` settings.
package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"regexp"
	"sort"
	"strings"
	"sync"
)

type multi []string

func (m *multi) String() string     { return strings.Join(*m, ",") }
func (m *multi) Set(v string) error { *m = append(*m, v); return nil }

// Lock is the lockfile. Every map is written with sorted keys (encoding/json does that), so a re-pin with
// nothing changed rewrites the same bytes.
type Lock struct {
	Version int `json:"version"`
	// Inputs are what the lock was resolved from; the module extension compares them with MODULE.bazel and
	// the catalog to say when a re-pin is due.
	Inputs struct {
		Libraries map[string]string `json:"libraries"` // alias → group:artifact:version
		Forces    map[string]string `json:"forces"`    // module → version
		Platforms []string          `json:"platforms"`
		Excludes  []string          `json:"excludes"`
	} `json:"inputs"`
	// Names are the Bazel target names, without the platform suffix: a catalog library by its alias
	// (kotlinx-coroutines-core → kotlinx_coroutines_core), anything else by its coordinate.
	Names map[string]string `json:"names"`
	// Skipped are catalog libraries with no variant for a platform (JVM-only libraries on native).
	Skipped   map[string][]string            `json:"skipped,omitempty"`
	Artifacts map[string]map[string]Resolved `json:"artifacts"` // module → platform → resolution
}

var nonIdent = regexp.MustCompile(`[^A-Za-z0-9_]`)

func main() {
	var libraries, platformNames, forces, repos, excludes multi
	toml := flag.String("toml", "gradle/libs.versions.toml", "the version catalog")
	out := flag.String("lock", "bazel/kmp/deps/maven_kmp.lock.json", "the lockfile to write")
	flag.Var(&libraries, "library", "a catalog library alias to resolve (repeatable)")
	flag.Var(&platformNames, "platform", "a platform to resolve for: jvm, linuxX64, … (repeatable)")
	flag.Var(&forces, "force", "module=version-ref: pin a module to a catalog version, as Gradle's substitutions do (repeatable)")
	flag.Var(&repos, "repository", "url[=group-regex,…]: a repository, in order, optionally limited to groups (repeatable)")
	flag.Var(&excludes, "exclude", "group:artifact never to resolve, whoever depends on it (repeatable)")
	flag.Parse()
	if err := run(*toml, *out, libraries, platformNames, forces, repos, excludes); err != nil {
		fmt.Fprintln(os.Stderr, "resolver:", err)
		os.Exit(1)
	}
}

func run(tomlPath, out string, libraries, platformNames, forceSpecs, repoSpecs, excludes []string) error {
	cat, err := ReadCatalog(tomlPath)
	if err != nil {
		return err
	}
	var repos []Repository
	for _, spec := range repoSpecs {
		url, groups, _ := strings.Cut(spec, "=")
		repo := Repository{URL: url}
		if groups != "" {
			for _, g := range strings.Split(groups, ",") {
				repo.Groups = append(repo.Groups, regexp.MustCompile("^(?:"+g+")$"))
			}
		}
		repos = append(repos, repo)
	}
	forced := map[string]string{}
	for _, spec := range forceSpecs {
		module, ref, ok := strings.Cut(spec, "=")
		v, known := cat.Versions[ref]
		if !ok || !known {
			return fmt.Errorf("--force %s: want module=version-ref with a [versions] entry", spec)
		}
		forced[module] = v
	}

	lock := Lock{Version: 1, Names: map[string]string{}, Artifacts: map[string]map[string]Resolved{}}
	lock.Inputs.Libraries = map[string]string{}
	lock.Inputs.Forces = forced
	lock.Inputs.Platforms = platformNames
	lock.Inputs.Excludes = excludes
	var roots []Coordinate
	for _, alias := range libraries {
		c, ok := cat.Libraries[alias]
		if !ok {
			return fmt.Errorf("--library %s: not in %s", alias, tomlPath)
		}
		roots = append(roots, c)
		lock.Inputs.Libraries[alias] = c.String()
		lock.Names[c.Module()] = nonIdent.ReplaceAllString(alias, "_")
	}

	resolver := NewResolver(NewFetcher(repos), forced, excludes)
	type result struct {
		platform string
		resolved map[string]Resolved
		skipped  []string
		err      error
	}
	results := make([]result, len(platformNames))
	var wg sync.WaitGroup
	for i, name := range platformNames {
		p, ok := platforms[name]
		if !ok {
			return fmt.Errorf("--platform %s: unknown", name)
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			resolved, skipped, err := resolver.Resolve(p, roots)
			results[i] = result{name, resolved, skipped, err}
		}()
	}
	wg.Wait()
	for _, r := range results {
		if r.err != nil {
			return fmt.Errorf("%s: %w", r.platform, r.err)
		}
		for module, res := range r.resolved {
			if lock.Artifacts[module] == nil {
				lock.Artifacts[module] = map[string]Resolved{}
			}
			lock.Artifacts[module][r.platform] = res
			if _, ok := lock.Names[module]; !ok {
				lock.Names[module] = nonIdent.ReplaceAllString(module, "_")
			}
		}
		if len(r.skipped) > 0 {
			if lock.Skipped == nil {
				lock.Skipped = map[string][]string{}
			}
			sort.Strings(r.skipped)
			lock.Skipped[r.platform] = r.skipped
		}
	}
	if err := checkNames(lock.Names); err != nil {
		return err
	}
	data, err := json.MarshalIndent(lock, "", "  ")
	if err != nil {
		return err
	}
	if err := os.WriteFile(out, append(data, '\n'), 0o644); err != nil {
		return err
	}
	fmt.Printf("Resolved %d libraries (%d modules) for %s into %s\n", len(roots), len(lock.Artifacts), strings.Join(platformNames, ", "), out)
	return nil
}

func checkNames(names map[string]string) error {
	seen := map[string]string{}
	for module, name := range names {
		if other, ok := seen[name]; ok {
			return fmt.Errorf("%s and %s would both be named %s", module, other, name)
		}
		seen[name] = module
	}
	return nil
}
