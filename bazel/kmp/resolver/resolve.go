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
}

var platforms = map[string]Platform{
	"jvm":        {Name: "jvm"},
	"linuxArm64": {Name: "linuxArm64", Konan: "linux_arm64"},
	"linuxX64":   {Name: "linuxX64", Konan: "linux_x64"},
	"macosArm64": {Name: "macosArm64", Konan: "macos_arm64"},
	"mingwX64":   {Name: "mingwX64", Konan: "mingw_x64"},
}

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
}

// excluded modules come from the Kotlin toolchain instead, at the compiler's own version, as KGP does.
func excluded(module string) bool {
	return strings.HasPrefix(module, "org.jetbrains.kotlin:kotlin-stdlib") ||
		strings.HasPrefix(module, "org.jetbrains.kotlin:kotlin-test")
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
// 1 = it fits without naming one (a plain Java library), 0 = it doesn't fit.
func wants(p Platform, usage string, a attrs) int {
	is := func(key, value string) bool { v, ok := a.str(key); return !ok || v == value }
	if v, _ := a.str("org.gradle.usage"); v != usage {
		return 0
	}
	if !is("org.gradle.category", "library") {
		return 0
	}
	platform, hasPlatform := a.str("org.jetbrains.kotlin.platform.type")
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
		if hasPlatform {
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
	files   []File
	api     []Coordinate
	runtime []Coordinate
}

type Resolver struct {
	fetch  *Fetcher
	forces map[string]string // module → version
	mu     sync.Mutex
	nodes  map[string]*node // platform|coordinate → node
}

func NewResolver(f *Fetcher, forces map[string]string) *Resolver {
	return &Resolver{fetch: f, forces: forces, nodes: map[string]*node{}}
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
		if p.Konan != "" {
			return nil, fmt.Errorf("%s: %w (it has no Gradle Module Metadata)", c, errNoVariant)
		}
		return r.loadPom(c, dir)
	}
	if err != nil {
		return nil, err
	}
	compileUsage, runtimeUsage := "java-api", "java-runtime"
	if p.Konan != "" {
		compileUsage, runtimeUsage = "kotlin-api", ""
	}
	api := pick(p, compileUsage, module.Variants)
	if api == nil {
		return nil, fmt.Errorf("%s: %w", c, errNoVariant)
	}
	var runtime *Variant
	if runtimeUsage != "" {
		runtime = pick(p, runtimeUsage, module.Variants)
	}
	// A KMP library's root module points at a per-platform module (`kotlinx-coroutines-core-jvm`).
	if api.AvailableAt != nil {
		at := api.AvailableAt
		return r.node(p, Coordinate{at.Group, at.Module, at.Version})
	}
	n := &node{}
	for _, f := range api.Files {
		n.files = append(n.files, File{Name: f.Name, URL: dir + "/" + f.URL, Sha256: f.Sha256})
	}
	n.api = dependencies(api.Dependencies)
	if runtime != nil {
		apiSet := map[string]bool{}
		for _, d := range n.api {
			apiSet[d.Module()] = true
		}
		for _, d := range dependencies(runtime.Dependencies) {
			if !apiSet[d.Module()] {
				n.runtime = append(n.runtime, d)
			}
		}
	}
	return n, nil
}

func dependencies(deps []Dependency) []Coordinate {
	var out []Coordinate
	for _, d := range deps {
		// A platform/BOM dependency only aligns versions; the highest-version rule already does that.
		if v, _ := attrs(d.Attributes).str("org.gradle.category"); v == "platform" || v == "enforced-platform" {
			continue
		}
		if excluded(d.Group+":"+d.Module) || d.version() == "" {
			continue
		}
		out = append(out, Coordinate{d.Group, d.Module, d.version()})
	}
	return out
}

func (r *Resolver) loadPom(c Coordinate, dir string) (*node, error) {
	pom, err := r.fetch.effectivePom(c)
	if err != nil {
		return nil, err
	}
	n := &node{}
	switch pom.pom.Packaging {
	case "", "jar", "bundle":
		url := fmt.Sprintf("%s/%s-%s.jar", dir, c.Artifact, c.Version)
		sum, err := r.fetch.sha256Of(url)
		if err != nil {
			return nil, err
		}
		n.files = []File{{Name: fmt.Sprintf("%s-%s.jar", c.Artifact, c.Version), URL: url, Sha256: sum}}
	}
	for _, d := range pom.pom.Dependencies {
		if d.Optional == "true" || (d.Type != "" && d.Type != "jar") {
			continue
		}
		dep := Coordinate{pom.expand(d.GroupID), pom.expand(d.ArtifactID), pom.expand(d.Version)}
		if dep.Version == "" {
			dep.Version = pom.managed[dep.Module()]
		}
		if excluded(dep.Module()) {
			continue
		}
		if dep.Version == "" || strings.Contains(dep.Version, "${") {
			return nil, fmt.Errorf("%s.pom: can't resolve the version of %s", c, dep.Module())
		}
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
// several versions gets the highest, unless it's forced (the catalog substitutions Gradle applies). Roots
// with no variant for the platform (a JVM-only library on native) are left out.
func (r *Resolver) Resolve(p Platform, roots []Coordinate) (map[string]Resolved, []string, error) {
	version := map[string]string{}
	request := func(c Coordinate) bool {
		if forced, ok := r.forces[c.Module()]; ok {
			c.Version = forced
		}
		if cur, ok := version[c.Module()]; ok && CompareVersions(c.Version, cur) <= 0 {
			return false
		}
		version[c.Module()] = c.Version
		return true
	}
	var skipped []string
	var queue []Coordinate
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
		if err != nil {
			return nil, nil, err
		}
		for _, d := range append(append([]Coordinate{}, n.api...), n.runtime...) {
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
		res := Resolved{Version: c.Version, Files: n.files}
		out[module] = res
		for _, d := range n.api {
			res.Deps = append(res.Deps, d.Module())
		}
		for _, d := range n.runtime {
			res.Runtime = append(res.Runtime, d.Module())
		}
		sort.Strings(res.Deps)
		sort.Strings(res.Runtime)
		res.Deps = dedupe(res.Deps)
		res.Runtime = dedupe(res.Runtime)
		out[module] = res
		for _, d := range append(append([]string{}, res.Deps...), res.Runtime...) {
			if err := walk(d); err != nil {
				return err
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
	return out, skipped, nil
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
