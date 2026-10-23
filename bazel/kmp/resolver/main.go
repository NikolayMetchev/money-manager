// The KMP Maven resolver (#1000): resolves MODULE.bazel's maven_kmp libraries for every platform from Gradle Module Metadata
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
	// Inputs are what the lock was resolved from; the module extension compares them with MODULE.bazel to say
	// when a re-pin is due.
	Inputs struct {
		Libraries map[string]string `json:"libraries"` // alias → group:artifact:version
		Forces    map[string]string `json:"forces"`    // module → version, on every platform
		// ClasspathForces only apply on the JVM and to common metadata, as Gradle's classpath substitutions
		// only match `*CompileClasspath`/`*RuntimeClasspath`/`*DependenciesMetadata`, not native klibraries.
		ClasspathForces map[string]string `json:"classpath_forces,omitempty"`
		Platforms       []string          `json:"platforms"`
		// LibraryPlatforms limit a library to some platforms (alias → platforms), as Gradle only resolves a
		// library for the source sets that declare it: the desktop Compose jars are no Android library.
		LibraryPlatforms map[string][]string `json:"library_platforms,omitempty"`
		Excludes         []string            `json:"excludes"`
	} `json:"inputs"`
	// Names are the Bazel target names, without the platform suffix: a library by its alias
	// (kotlinx-coroutines-core → kotlinx_coroutines_core), anything else by its coordinate.
	Names map[string]string `json:"names"`
	// Packages are what each module's files declare, for Gazelle's import resolution.
	Packages map[string][]string `json:"packages"`
	// Skipped are libraries with no variant for a platform (JVM-only libraries on native).
	Skipped   map[string][]string            `json:"skipped,omitempty"`
	Artifacts map[string]map[string]Resolved `json:"artifacts"` // module → platform → resolution
}

var nonIdent = regexp.MustCompile(`[^A-Za-z0-9_]`)

func main() {
	var libraries, libraryPlatforms, platformNames, forces, classpathForces, repos, excludes multi
	kotlin := flag.String("kotlin", "", "the Kotlin release the toolchain's stdlib and kotlin-test are")
	out := flag.String("lock", "bazel/kmp/deps/maven_kmp.lock.json", "the lockfile to write")
	flag.Var(&libraries, "library", "alias=group:artifact:version: a library to resolve (repeatable)")
	flag.Var(&libraryPlatforms, "library-platforms", "alias=platform,…: resolve a library for these platforms only (repeatable)")
	flag.Var(&platformNames, "platform", "a platform to resolve for: jvm, linuxX64, … (repeatable)")
	flag.Var(&forces, "force", "module=version: pin a module on every platform (repeatable)")
	flag.Var(&classpathForces, "classpath-force", "module=version: pin a module on the JVM and in common metadata only (repeatable)")
	flag.Var(&repos, "repository", "url[=group-regex,…]: a repository, in order, optionally limited to groups (repeatable)")
	flag.Var(&excludes, "exclude", "group:artifact never to resolve, whoever depends on it (repeatable)")
	flag.Parse()
	if err := run(*kotlin, *out, libraries, libraryPlatforms, platformNames, forces, classpathForces, repos, excludes); err != nil {
		fmt.Fprintln(os.Stderr, "resolver:", err)
		os.Exit(1)
	}
}

func run(kotlin, out string, libraries, libraryPlatformSpecs, platformNames, forceSpecs, classpathForceSpecs, repoSpecs, excludes []string) error {
	if kotlin == "" {
		return fmt.Errorf("--kotlin is required")
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
	parseForces := func(flag string, specs []string) (map[string]string, error) {
		forced := map[string]string{}
		for _, spec := range specs {
			module, v, ok := strings.Cut(spec, "=")
			if !ok || v == "" || strings.Count(module, ":") != 1 {
				return nil, fmt.Errorf("--%s %s: want group:artifact=version", flag, spec)
			}
			forced[module] = v
		}
		return forced, nil
	}
	forced, err := parseForces("force", forceSpecs)
	if err != nil {
		return err
	}
	classpathForced, err := parseForces("classpath-force", classpathForceSpecs)
	if err != nil {
		return err
	}

	lock := Lock{Version: 1, Names: map[string]string{}, Packages: map[string][]string{}, Artifacts: map[string]map[string]Resolved{}}
	lock.Inputs.Libraries = map[string]string{}
	lock.Inputs.Forces = forced
	if len(classpathForced) > 0 {
		lock.Inputs.ClasspathForces = classpathForced
	}
	lock.Inputs.Platforms = platformNames
	// Never null: MODULE.bazel's empty list must read back as one.
	lock.Inputs.Excludes = append([]string{}, excludes...)
	only := map[string]map[string]bool{}
	for _, spec := range libraryPlatformSpecs {
		alias, names, ok := strings.Cut(spec, "=")
		if !ok || names == "" {
			return fmt.Errorf("--library-platforms %s: want alias=platform,…", spec)
		}
		only[alias] = map[string]bool{}
		for _, name := range strings.Split(names, ",") {
			if _, known := platforms[name]; !known {
				return fmt.Errorf("--library-platforms %s: unknown platform %s", spec, name)
			}
			only[alias][name] = true
		}
		if lock.Inputs.LibraryPlatforms == nil {
			lock.Inputs.LibraryPlatforms = map[string][]string{}
		}
		lock.Inputs.LibraryPlatforms[alias] = strings.Split(names, ",")
	}
	var roots []Coordinate
	var rootAliases []string
	for _, spec := range libraries {
		alias, coordinates, ok := strings.Cut(spec, "=")
		if !ok {
			return fmt.Errorf("--library %s: want alias=group:artifact:version", spec)
		}
		c, err := ParseCoordinate(coordinates)
		if err != nil {
			return fmt.Errorf("--library %s: %w", spec, err)
		}
		roots = append(roots, c)
		rootAliases = append(rootAliases, alias)
		lock.Inputs.Libraries[alias] = c.String()
		lock.Names[c.Module()] = nonIdent.ReplaceAllString(alias, "_")
	}

	resolver := NewResolver(NewFetcher(repos), forced, excludes)
	resolver.classpathForces = classpathForced
	for _, name := range platformNames {
		if p, ok := platforms[name]; ok && !p.Common {
			resolver.consumers = append(resolver.consumers, p)
		}
	}
	// The JVM's and Kotlin/Native's stdlib and kotlin-test come from the toolchain. Common code compiles against
	// their metadata jars, and the browser platforms against their klibs, at the toolchain's version.
	toolchain := []Coordinate{
		{"org.jetbrains.kotlin", "kotlin-stdlib", kotlin},
		{"org.jetbrains.kotlin", "kotlin-test", kotlin},
	}
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
			var platformRoots []Coordinate
			for j, c := range roots {
				if limit, ok := only[rootAliases[j]]; !ok || limit[name] {
					platformRoots = append(platformRoots, c)
				}
			}
			if p.Common || p.Web != "" {
				platformRoots = append(platformRoots, toolchain...)
			}
			resolved, skipped, err := resolver.Resolve(p, platformRoots)
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
	fetcher := resolver.fetch
	fromToolchain := map[string]bool{}
	for _, c := range toolchain {
		fromToolchain[c.Module()] = true
	}
	for module, byPlatform := range lock.Artifacts {
		set := map[string]bool{}
		// A metadata jar declares what the module's platform files do, and the stdlib is no import's library.
		for name, res := range byPlatform {
			if platforms[name].Common || fromToolchain[module] {
				continue
			}
			for _, file := range res.Files {
				pkgs, err := fetcher.packagesOf(file)
				if err != nil {
					return err
				}
				for _, p := range pkgs {
					set[p] = true
				}
			}
		}
		var pkgs []string
		for p := range set {
			pkgs = append(pkgs, p)
		}
		sort.Strings(pkgs)
		lock.Packages[module] = pkgs
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
