package main

import (
	"errors"
	"fmt"
	"sort"
	"strconv"
	"strings"
	"sync"
)

// Platform is one compilation target, and how Gradle picks a library's variant for it.
type Platform struct {
	Name string // jvm, linuxX64, …
	// konan's target name for a native platform, empty on the JVM.
	Konan string
	// Web is the Kotlin/JS IR or Kotlin/Wasm platform type (`js`, `wasm`) of a browser platform, empty elsewhere.
	Web string
	// Common is a `metadata` platform: the common metadata variant (common.go).
	Common bool
	// SeesWeb is the common metadata platform of modules that also compile for the browser (`metadataWeb`): the
	// source sets its consumers share include the web platforms'. `metadata` leaves them out, so a module without
	// web targets keeps seeing what JVM, Android and native share (kotlinx-coroutines' `concurrentMain`).
	SeesWeb bool
	// Android is the KMP Android library target's compilation (#1021): a library's Android variant (an AAR), or
	// its JVM one.
	Android bool
}

var platforms = map[string]Platform{
	"android":        {Name: "android", Android: true},
	"jvm":            {Name: "jvm"},
	"linuxArm64":     {Name: "linuxArm64", Konan: "linux_arm64"},
	"linuxX64":       {Name: "linuxX64", Konan: "linux_x64"},
	"macosArm64":     {Name: "macosArm64", Konan: "macos_arm64"},
	"mingwX64":       {Name: "mingwX64", Konan: "mingw_x64"},
	"wasmJs":         {Name: "wasmJs", Web: "wasm"},
	"js":             {Name: "js", Web: "js"},
	metadataPlatform: {Name: metadataPlatform, Common: true},
	metadataWeb:      {Name: metadataWeb, Common: true, SeesWeb: true},
}

// IsKlib is a platform whose libraries are klibs Gradle resolves by `kotlin-api` usage alone (no runtime variant
// to follow, no POM-only fallback): native and the browser.
func (p Platform) IsKlib() bool { return p.Konan != "" || p.Web != "" }

const jvmVersion = 25

// File is one downloaded file of a resolved library.
type File struct {
	Name   string `json:"name"`
	URL    string `json:"url"`
	Sha256 string `json:"sha256"`
}

// Resolved is a library on one platform: its files and the libraries it depends on, by module.
type Resolved struct {
	Version string   `json:"version"`
	Files   []File   `json:"files"`
	Deps    []string `json:"deps,omitempty"`         // api: on dependents' compile classpath too
	Runtime []string `json:"runtime_deps,omitempty"` // runtime only (JVM)
	// CompileOnly are api dependencies its runtime variant leaves out (log4j-core's OSGi annotations): on
	// dependents' compile classpath only, as Gradle's runtime classpath follows the runtime variant (JVM).
	CompileOnly []string `json:"compile_only_deps,omitempty"`
	// SourceSets are the klib directories of a `metadata` jar our common code sees.
	SourceSets []string `json:"source_sets,omitempty"`
}

// fromToolchain modules come from the Kotlin toolchain instead, at the compiler's own version, as KGP does.
func fromToolchain(module string) bool {
	return strings.HasPrefix(module, "org.jetbrains.kotlin:kotlin-stdlib") ||
		strings.HasPrefix(module, "org.jetbrains.kotlin:kotlin-test")
}

func (r *Resolver) forced(p Platform, module string) (string, bool) {
	if v, ok := r.forces[module]; ok {
		return v, true
	}
	if !p.IsKlib() {
		v, ok := r.classpathForces[module]
		return v, ok
	}
	return "", false
}

func (r *Resolver) excluded(module string) bool {
	return fromToolchain(module) || r.excludes[module]
}

type attrs map[string]any

func (a attrs) str(key string) (string, bool) {
	v, ok := a[key]
	if !ok {
		return "", false
	}
	switch t := v.(type) {
	case string:
		return t, true
	case float64:
		return strconv.Itoa(int(t)), true
	}
	return fmt.Sprint(v), true
}

// wants decides whether a variant fits the platform (and usage), and how well: 2 = it names the platform,
// 1 = it fits without naming one (a plain Java library, or one that names no usage either), 0 = it doesn't fit.
// As in Gradle, an attribute the variant leaves out is compatible: kotlin-stdlib-common's only variant names
// nothing but its category.
func wants(p Platform, usage string, a attrs) int {
	is := func(key, value string) bool { v, ok := a.str(key); return !ok || v == value }
	if !is("org.gradle.usage", usage) {
		return 0
	}
	if !is("org.gradle.category", "library") {
		return 0
	}
	platform, hasPlatform := a.str("org.jetbrains.kotlin.platform.type")
	if p.Android {
		return wantsAndroid(usage, a, platform, hasPlatform)
	}
	if p.Web != "" {
		return wantsWeb(p, a, platform)
	}
	if p.Konan == "" {
		if hasPlatform && platform != "jvm" {
			return 0
		}
		if !is("org.gradle.jvm.environment", "standard-jvm") || !is("org.gradle.libraryelements", "jar") {
			return 0
		}
		if v, ok := a.str("org.gradle.jvm.version"); ok {
			if n, err := strconv.Atoi(v); err == nil && n > jvmVersion {
				return 0
			}
		}
		if _, hasUsage := a.str("org.gradle.usage"); hasPlatform && hasUsage {
			return 2
		}
		return 1
	}
	if platform != "native" {
		return 0
	}
	if v, _ := a.str("org.jetbrains.kotlin.native.target"); v != p.Konan {
		return 0
	}
	return 2
}

// wantsWeb is wants for a browser platform: Kotlin/Wasm's `js` target (not WASI), or Kotlin/JS's IR compiler (not
// the legacy one).
func wantsWeb(p Platform, a attrs, platform string) int {
	if platform != p.Web {
		return 0
	}
	switch p.Web {
	case "wasm":
		if v, _ := a.str("org.jetbrains.kotlin.wasm.target"); v != "js" {
			return 0
		}
	case "js":
		if v, ok := a.str("org.jetbrains.kotlin.js.compiler"); ok && v != "ir" {
			return 0
		}
	}
	return 2
}

// wantsAndroid is wants for the Android target: KGP's androidJvm variant first (4), then a JVM one, as KGP's
// compatibility rule lets an Android compilation take (2); among equals, AGP's `android` environment wins
// (Guava's android flavour over its jre one). An AAR is as good as a jar: AGP transforms it to its classes.
func wantsAndroid(usage string, a attrs, platform string, hasPlatform bool) int {
	score := 1
	switch {
	case !hasPlatform:
	case platform == "androidJvm":
		score = 4
	case platform == "jvm":
		score = 2
	default:
		return 0
	}
	if v, ok := a.str("org.gradle.libraryelements"); ok && v != "jar" && v != "aar" {
		return 0
	}
	if v, ok := a.str("org.gradle.jvm.version"); ok {
		if n, err := strconv.Atoi(v); err == nil && n > jvmVersion {
			return 0
		}
	}
	env, _ := a.str("org.gradle.jvm.environment")
	switch env {
	case "android":
		score++
	case "", "standard-jvm":
	default:
		return 0
	}
	if _, hasUsage := a.str("org.gradle.usage"); !hasUsage && score > 1 {
		score = 1
	}
	return score
}

func pick(p Platform, usage string, variants []Variant) *Variant {
	best, bestScore := -1, 0
	for i, v := range variants {
		if score := wants(p, usage, v.Attributes); score > bestScore {
			best, bestScore = i, score
		}
	}
	if best < 0 {
		return nil
	}
	return &variants[best]
}

var errNoVariant = errors.New("no variant for this platform")

// node is what one library version contributes on a platform, before versions are settled.
type node struct {
	files      []File
	api         []Coordinate
	runtime     []Coordinate
	compileOnly []Coordinate
	sourceSets  []string
	// platforms are the BOMs it depends on (`platform(…)`), and constraints its own dependencyConstraints and,
	// on a BOM, the versions it aligns. Neither is a dependency: they only raise versions (Resolve).
	platforms   []Coordinate
	constraints []Coordinate
}

type Resolver struct {
	fetch  *Fetcher
	forces map[string]string // module → version
	// classpathForces apply on the JVM and to common metadata, not on native or the browser (Gradle's classpath
	// substitutions).
	classpathForces map[string]string
	excludes        map[string]bool
	// consumers are the platforms common code is compiled for, whose variants decide what it sees.
	consumers []Platform
	mu        sync.Mutex
	nodes     map[string]*node // platform|coordinate → node
}

func NewResolver(f *Fetcher, forces map[string]string, excludes []string) *Resolver {
	r := &Resolver{fetch: f, forces: forces, excludes: map[string]bool{}, nodes: map[string]*node{}}
	for _, e := range excludes {
		r.excludes[e] = true
	}
	return r
}

func (r *Resolver) node(p Platform, c Coordinate) (*node, error) {
	key := p.Name + "|" + c.String()
	r.mu.Lock()
	if n, ok := r.nodes[key]; ok {
		r.mu.Unlock()
		return n, nil
	}
	r.mu.Unlock()
	n, err := r.load(p, c)
	if err != nil {
		return nil, err
	}
	r.mu.Lock()
	r.nodes[key] = n
	r.mu.Unlock()
	return n, nil
}

func (r *Resolver) load(p Platform, c Coordinate) (*node, error) {
	dir, err := r.fetch.moduleDir(c)
	if err != nil {
		return nil, err
	}
	module, err := r.fetch.gradleModule(dir, c)
	if errors.Is(err, errNotFound) {
		if p.IsKlib() || p.Common {
			return nil, fmt.Errorf("%s: %w (it has no Gradle Module Metadata)", c, errNoVariant)
		}
		return r.loadPom(p, c, dir)
	}
	if err != nil {
		return nil, err
	}
	if p.Common {
		return r.loadMetadata(p, c, dir, module)
	}
	compileUsage, runtimeUsage := "java-api", "java-runtime"
	if p.IsKlib() {
		compileUsage, runtimeUsage = "kotlin-api", ""
	}
	api := pick(p, compileUsage, module.Variants)
	if api == nil && runtimeUsage != "" {
		// Gradle's Java ecosystem takes a runtime variant for a compile classpath when there's no api one: the
		// Compose Gradle plugin publishes only its shadowed runtime jar.
		api = pick(p, runtimeUsage, module.Variants)
	}
	if api == nil {
		return nil, fmt.Errorf("%s: %w", c, errNoVariant)
	}
	var runtime *Variant
	if runtimeUsage != "" {
		runtime = pick(p, runtimeUsage, module.Variants)
	}
	if isStubs(api) {
		return nil, fmt.Errorf("%s: %w (only stubs)", c, errNoVariant)
	}
	// A KMP library's root module points at a per-platform module (`kotlinx-coroutines-core-jvm`).
	if api.AvailableAt != nil {
		at := api.AvailableAt
		if r.excluded(at.Group + ":" + at.Module) {
			// Excluding the module it points at leaves this one with nothing, as in Gradle.
			return &node{}, nil
		}
		return r.node(p, Coordinate{at.Group, at.Module, at.Version})
	}
	n := &node{}
	for _, f := range api.Files {
		n.files = append(n.files, File{Name: f.Name, URL: dir + "/" + f.URL, Sha256: f.Sha256})
	}
	n.api = r.dependencies(api.Dependencies)
	n.platforms = r.platformDependencies(api.Dependencies)
	n.constraints = r.constraints(api.DependencyConstraints)
	if runtime != nil {
		n.platforms = append(n.platforms, r.platformDependencies(runtime.Dependencies)...)
		n.constraints = append(n.constraints, r.constraints(runtime.DependencyConstraints)...)
		apiSet := map[string]bool{}
		for _, d := range n.api {
			apiSet[d.Module()] = true
		}
		runtimeSet := map[string]bool{}
		for _, d := range r.dependencies(runtime.Dependencies) {
			runtimeSet[d.Module()] = true
			if !apiSet[d.Module()] {
				n.runtime = append(n.runtime, d)
			}
		}
		// The variants are one when they're the same (a POM-only library, or the api variant Gradle falls back to).
		if runtime != api {
			var api []Coordinate
			for _, d := range n.api {
				if runtimeSet[d.Module()] {
					api = append(api, d)
				} else {
					n.compileOnly = append(n.compileOnly, d)
				}
			}
			n.api = api
		}
	}
	return n, nil
}

// isStubs is an AndroidX variant that only stands in for a platform the library doesn't support (Compose
// Foundation's `jvmStubs`, `linuxx64Stubs`): its classes throw. Gradle would resolve it, but nothing here
// compiles against one (the desktop app takes JetBrains' Compose instead), so the library has no variant on that
// platform, and Gazelle doesn't mistake it for one that serves both.
func isStubs(v *Variant) bool {
	if strings.Contains(v.Name, "Stubs") {
		return true
	}
	return v.AvailableAt != nil && strings.HasSuffix(v.AvailableAt.Module, "stubs")
}

func isPlatform(d Dependency) bool {
	v, _ := attrs(d.Attributes).str("org.gradle.category")
	return v == "platform" || v == "enforced-platform"
}

// platformDependencies are the BOMs among deps: Resolve applies their constraints.
func (r *Resolver) platformDependencies(deps []Dependency) []Coordinate {
	var out []Coordinate
	for _, d := range deps {
		if isPlatform(d) && d.version() != "" {
			out = append(out, Coordinate{d.Group, d.Module, d.version()})
		}
	}
	return out
}

func (r *Resolver) constraints(deps []Dependency) []Coordinate {
	var out []Coordinate
	for _, d := range deps {
		if !r.excluded(d.Group+":"+d.Module) && d.version() != "" {
			out = append(out, Coordinate{d.Group, d.Module, d.version()})
		}
	}
	return out
}

// platform loads a BOM's constraints (every variant's: a BOM's variants all align the same modules).
func (r *Resolver) platform(c Coordinate) ([]Coordinate, error) {
	key := "platform|" + c.String()
	r.mu.Lock()
	if n, ok := r.nodes[key]; ok {
		r.mu.Unlock()
		return n.constraints, nil
	}
	r.mu.Unlock()
	dir, err := r.fetch.moduleDir(c)
	if err != nil {
		return nil, err
	}
	n := &node{}
	module, err := r.fetch.gradleModule(dir, c)
	switch {
	case errors.Is(err, errNotFound):
		// A POM BOM (kotlinx-serialization-bom) constrains what its dependencyManagement lists.
		pom, err := r.fetch.effectivePom(c)
		if err != nil {
			return nil, err
		}
		for module, version := range pom.managed {
			if ga := strings.SplitN(module, ":", 2); len(ga) == 2 && !r.excluded(module) && version != "" {
				n.constraints = append(n.constraints, Coordinate{ga[0], ga[1], version})
			}
		}
	case err != nil:
		return nil, err
	default:
		for _, v := range module.Variants {
			n.constraints = append(n.constraints, r.constraints(v.DependencyConstraints)...)
		}
	}
	r.mu.Lock()
	r.nodes[key] = n
	r.mu.Unlock()
	return n.constraints, nil
}

func (r *Resolver) dependencies(deps []Dependency) []Coordinate {
	var out []Coordinate
	for _, d := range deps {
		// A platform/BOM dependency is no library: Resolve applies its constraints (platformDependencies).
		if isPlatform(d) {
			continue
		}
		if r.excluded(d.Group+":"+d.Module) || d.version() == "" {
			continue
		}
		out = append(out, Coordinate{d.Group, d.Module, d.version()})
	}
	return out
}

func (r *Resolver) loadPom(p Platform, c Coordinate, dir string) (*node, error) {
	pom, err := r.fetch.effectivePom(c)
	if err != nil {
		return nil, err
	}
	n := &node{}
	ext := ""
	switch pom.pom.Packaging {
	case "", "jar", "bundle":
		ext = "jar"
	case "aar":
		// A POM-only Android library (Play services): Android only.
		if !p.Android {
			return nil, fmt.Errorf("%s: %w (an AAR)", c, errNoVariant)
		}
		ext = "aar"
	}
	if ext != "" {
		url := fmt.Sprintf("%s/%s-%s.%s", dir, c.Artifact, c.Version, ext)
		sum, err := r.fetch.sha256Of(url)
		if err != nil {
			return nil, err
		}
		n.files = []File{{Name: fmt.Sprintf("%s-%s.%s", c.Artifact, c.Version, ext), URL: url, Sha256: sum}}
	}
	for _, d := range pom.pom.Dependencies {
		aar := p.Android && d.Type == "aar"
		if d.Optional == "true" || (d.Type != "" && d.Type != "jar" && !aar) || (d.Scope != "" && d.Scope != "compile" && d.Scope != "runtime") {
			continue
		}
		dep := Coordinate{pom.expand(d.GroupID), pom.expand(d.ArtifactID), pom.expand(d.Version)}
		if dep.Version == "" {
			dep.Version = pom.managed[dep.Module()]
		}
		if r.excluded(dep.Module()) {
			continue
		}
		if dep.Version == "" || strings.Contains(dep.Version, "${") {
			return nil, fmt.Errorf("%s.pom: can't resolve the version of %s", c, dep.Module())
		}
		v, err := pomVersion(dep.Version)
		if err != nil {
			return nil, fmt.Errorf("%s.pom: %s: %w", c, dep.Module(), err)
		}
		dep.Version = v
		switch d.Scope {
		case "", "compile":
			n.api = append(n.api, dep)
		case "runtime":
			n.runtime = append(n.runtime, dep)
		}
	}
	return n, nil
}

// Resolve resolves the roots and everything they need on one platform. As in Gradle, a module requested at
// several versions gets the highest, unless it's forced (the substitutions Gradle applies). Roots
// with no variant for the platform (a JVM-only library on native) are left out.
func (r *Resolver) Resolve(p Platform, roots []Coordinate) (map[string]Resolved, []string, error) {
	version := map[string]string{}
	// Gradle's constraints (a node's dependencyConstraints, a BOM's alignment) join the highest-version rule
	// for a module something depends on, and never bring one in. A force still wins.
	constraint := map[string]string{}
	request := func(c Coordinate) bool {
		if forced, ok := r.forced(p, c.Module()); ok {
			c.Version = forced
		} else if v, ok := constraint[c.Module()]; ok && CompareVersions(v, c.Version) > 0 {
			c.Version = v
		}
		if cur, ok := version[c.Module()]; ok && CompareVersions(c.Version, cur) <= 0 {
			return false
		}
		version[c.Module()] = c.Version
		return true
	}
	var queue []Coordinate
	// constrain records a constraint, and re-requests a module already in the graph at a lower version.
	constrain := func(c Coordinate) {
		if v, ok := constraint[c.Module()]; ok && CompareVersions(c.Version, v) <= 0 {
			return
		}
		constraint[c.Module()] = c.Version
		if _, ok := version[c.Module()]; ok && request(c) {
			queue = append(queue, c)
		}
	}
	platformVersion := map[string]string{}
	applyPlatform := func(c Coordinate) error {
		// A BOM a substitution pins (the convention's kotlinx-coroutines-bom) loses the platform attribute the
		// request carried, so Gradle resolves it as a library and applies none of its constraints: Android's
		// classpath keeps play-services-auth's kotlinx-coroutines-play-services 1.8.0 beside coroutines 1.11.0.
		if _, ok := r.forced(p, c.Module()); ok {
			return nil
		}
		if cur, ok := platformVersion[c.Module()]; ok && CompareVersions(c.Version, cur) <= 0 {
			return nil
		}
		platformVersion[c.Module()] = c.Version
		cs, err := r.platform(c)
		if err != nil {
			return err
		}
		for _, k := range cs {
			constrain(k)
		}
		return nil
	}
	var skipped []string
	for _, root := range roots {
		if _, err := r.node(p, root); errors.Is(err, errNoVariant) {
			skipped = append(skipped, root.Module())
			continue
		} else if err != nil {
			return nil, nil, err
		}
		if request(root) {
			queue = append(queue, root)
		}
	}
	for len(queue) > 0 {
		c := queue[0]
		queue = queue[1:]
		c.Version = version[c.Module()]
		n, err := r.node(p, c)
		if errors.Is(err, errNoVariant) {
			// Gradle drops what only a superseded version asked for (kotlin-stdlib 1.6's POM wants
			// kotlin-stdlib-common, which has no JVM variant since 1.9). The walk below fails if it's still reached.
			continue
		}
		if err != nil {
			return nil, nil, err
		}
		for _, k := range n.constraints {
			constrain(k)
		}
		for _, b := range n.platforms {
			if err := applyPlatform(b); err != nil {
				return nil, nil, err
			}
		}
		for _, d := range append(append(append([]Coordinate{}, n.api...), n.runtime...), n.compileOnly...) {
			if request(d) {
				queue = append(queue, d)
			}
		}
	}

	// Keep only what the settled versions still reach.
	out := map[string]Resolved{}
	var walk func(module string) error
	walk = func(module string) error {
		if _, ok := out[module]; ok {
			return nil
		}
		ga := strings.SplitN(module, ":", 2)
		c := Coordinate{ga[0], ga[1], version[module]}
		n, err := r.node(p, c)
		if err != nil {
			return err
		}
		res := Resolved{Version: c.Version, Files: n.files, SourceSets: n.sourceSets}
		out[module] = res
		for _, d := range n.api {
			res.Deps = append(res.Deps, d.Module())
		}
		for _, d := range n.runtime {
			res.Runtime = append(res.Runtime, d.Module())
		}
		for _, d := range n.compileOnly {
			res.CompileOnly = append(res.CompileOnly, d.Module())
		}
		sort.Strings(res.Deps)
		sort.Strings(res.Runtime)
		sort.Strings(res.CompileOnly)
		res.Deps = dedupe(res.Deps)
		res.Runtime = dedupe(res.Runtime)
		res.CompileOnly = dedupe(res.CompileOnly)
		out[module] = res
		for _, d := range append(append(append([]string{}, res.Deps...), res.Runtime...), res.CompileOnly...) {
			if err := walk(d); err != nil {
				return fmt.Errorf("%w\n  needed by %s", err, c)
			}
		}
		return nil
	}
	for _, root := range roots {
		if contains(skipped, root.Module()) {
			continue
		}
		if err := walk(root.Module()); err != nil {
			return nil, nil, err
		}
	}
	breakCycles(out, roots, skipped)
	return out, skipped, nil
}

// breakCycles drops each dependency that closes a cycle (AGP's gradle-api has a runtime dependency on gradle,
// which depends on gradle-api). Gradle resolves a cycle as one graph; Bazel targets can't form one. Walking from
// the roots, the edge back to a module still being walked goes, so everything the roots reached stays reached,
// though a dependent of a later member no longer brings the earlier ones.
func breakCycles(out map[string]Resolved, roots []Coordinate, skipped []string) {
	const (
		walking = 1
		done    = 2
	)
	state := map[string]int{}
	var visit func(module string)
	visit = func(module string) {
		state[module] = walking
		res := out[module]
		keep := func(deps []string) []string {
			var kept []string
			for _, d := range deps {
				if state[d] == walking {
					continue
				}
				if state[d] == 0 {
					visit(d)
				}
				kept = append(kept, d)
			}
			return kept
		}
		res.Deps = keep(res.Deps)
		res.Runtime = keep(res.Runtime)
		res.CompileOnly = keep(res.CompileOnly)
		out[module] = res
		state[module] = done
	}
	for _, root := range roots {
		if !contains(skipped, root.Module()) && state[root.Module()] == 0 {
			visit(root.Module())
		}
	}
}

func dedupe(s []string) []string {
	var out []string
	for i, v := range s {
		if i == 0 || v != s[i-1] {
			out = append(out, v)
		}
	}
	return out
}

func contains(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}

// CompareVersions orders versions the way Gradle does, closely enough for library versions: numeric parts
// numerically, a release above its own pre-releases (1.0 > 1.0-rc1), and dev < alpha < beta < milestone < rc.
func CompareVersions(a, b string) int {
	pa, pb := versionParts(a), versionParts(b)
	for i := 0; i < len(pa) || i < len(pb); i++ {
		if i >= len(pa) {
			return -tail(pb[i])
		}
		if i >= len(pb) {
			return tail(pa[i])
		}
		if c := comparePart(pa[i], pb[i]); c != 0 {
			return c
		}
	}
	return 0
}

// tail is how an extra trailing part compares with nothing: a number or release qualifier makes the version
// higher (1.0.1 > 1.0, 1.0-final > 1.0), a pre-release qualifier lower (1.0-rc < 1.0).
func tail(part string) int {
	if _, err := strconv.Atoi(part); err == nil {
		return 1
	}
	if qualifierRank(part) > qualifierRank("") {
		return 1
	}
	return -1
}

var qualifiers = map[string]int{"dev": 0, "alpha": 1, "a": 1, "beta": 2, "b": 2, "milestone": 3, "m": 3, "rc": 4, "cr": 4, "snapshot": 5, "": 6, "final": 7, "ga": 7, "release": 7, "sp": 8}

func qualifierRank(q string) int {
	if r, ok := qualifiers[strings.ToLower(q)]; ok {
		return r
	}
	return 5 // an unknown qualifier sorts like a snapshot: below the release
}

func comparePart(a, b string) int {
	na, ea := strconv.Atoi(a)
	nb, eb := strconv.Atoi(b)
	switch {
	case ea == nil && eb == nil:
		return cmp(na, nb)
	case ea == nil:
		return 1
	case eb == nil:
		return -1
	}
	if ra, rb := qualifierRank(a), qualifierRank(b); ra != rb {
		return cmp(ra, rb)
	}
	return strings.Compare(a, b)
}

func cmp(a, b int) int {
	switch {
	case a < b:
		return -1
	case a > b:
		return 1
	}
	return 0
}

func versionParts(v string) []string {
	var parts []string
	cur := ""
	digit := false
	for _, ch := range v {
		if ch == '.' || ch == '-' || ch == '_' || ch == '+' {
			if cur != "" {
				parts = append(parts, cur)
			}
			cur = ""
			continue
		}
		isDigit := ch >= '0' && ch <= '9'
		if cur != "" && isDigit != digit {
			parts = append(parts, cur)
			cur = ""
		}
		cur += string(ch)
		digit = isDigit
	}
	if cur != "" {
		parts = append(parts, cur)
	}
	return parts
}

// pomVersion reads a POM dependency's version: a plain one, or a hard requirement `[1.2]` (skiko-awt-runtime's
// skiko-awt), which is that version. A real range would need the repository's version listing, as Gradle reads.
func pomVersion(v string) (string, error) {
	if !strings.ContainsAny(v, "[](),") {
		return v, nil
	}
	if inner, ok := strings.CutPrefix(v, "["); ok {
		if inner, ok = strings.CutSuffix(inner, "]"); ok && !strings.Contains(inner, ",") {
			return inner, nil
		}
	}
	return "", fmt.Errorf("version range %s isn't supported, only an exact [version]", v)
}
