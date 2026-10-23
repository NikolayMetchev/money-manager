package kmp

import (
	"log"
	"sort"
	"strings"

	"github.com/bazelbuild/bazel-gazelle/config"
	"github.com/bazelbuild/bazel-gazelle/label"
	"github.com/bazelbuild/bazel-gazelle/repo"
	"github.com/bazelbuild/bazel-gazelle/resolve"
	"github.com/bazelbuild/bazel-gazelle/rule"
)

// Imports indexes a kmp_library by its packages: one package per module (verifyUniquePackages) makes an
// import's package name exactly one module.
func (*kmpLang) Imports(c *config.Config, r *rule.Rule, f *rule.File) []resolve.ImportSpec {
	if r.Kind() != "kmp_library" {
		return nil
	}
	pkgs, _ := r.PrivateAttr(packagesKey).([]string)
	specs := make([]resolve.ImportSpec, 0, len(pkgs))
	for _, p := range pkgs {
		specs = append(specs, resolve.ImportSpec{Lang: languageName, Imp: p})
	}
	return specs
}

func (*kmpLang) Embeds(r *rule.Rule, from label.Label) []label.Label { return nil }

// Libraries the toolchain, the distribution and the Android SDK (android.jar, on every Android compile) provide:
// no dependency to add. The browser's DOM (`kotlinx.browser`, `org.w3c`) isn't: it's the kotlinx-browser library.
var builtin = []string{"kotlin.", "java.", "javax.", "sun.", "kotlinx.cinterop.", "platform.", "android."}

// Dependencies kotlin.* names stand for, which aren't Maven libraries here.
var kotlinLibraries = map[string]string{
	"kotlin.test":         "//bazel/kmp/deps:kotlin_test",
	"kotlin.reflect.full": "//bazel/kmp/deps:kotlin_reflect",
	"kotlin.reflect.jvm":  "//bazel/kmp/deps:kotlin_reflect",
}

// candidates are the packages an import could name, most specific first: `a.b.C.d` may be in `a.b.C`
// (a class's member), `a.b` (a class), …
func candidates(name string) []string {
	name = strings.TrimSuffix(name, ".*")
	parts := strings.Split(name, ".")
	var out []string
	for i := len(parts); i > 0; i-- {
		out = append(out, strings.Join(parts[:i], "."))
	}
	return out
}

func (*kmpLang) Resolve(c *config.Config, ix *resolve.RuleIndex, rc *repo.RemoteCache, r *rule.Rule, raw interface{}, from label.Label) {
	imp, ok := raw.(imports)
	if !ok || imp.byPlatform == nil {
		return
	}
	kc := getConfig(c)
	exported := kc.exported()
	// The module itself and, for tests, the module under test, written as moduleLabel writes them.
	self := map[string]bool{moduleLabel(from, from): true}
	if imp.associate != "" {
		self[moduleLabel(label.New(from.Repo, from.Pkg, imp.associate), from)] = true
	}
	deps := map[string]map[string]bool{}
	for _, platform := range platforms {
		deps[platform.name] = map[string]bool{}
	}
	add := func(platform, dep string) {
		if imp.test {
			dep = forTests(dep)
		}
		if dep != "" && !self[dep] {
			deps[platform][dep] = true
		}
	}
	for platform, names := range imp.byPlatform {
		named := imp.named(platform)
		below := imp.leaves.below(platform)
		for _, name := range names {
			// The library every compilation the source set is part of has, as Gradle's metadata resolves one.
			dep, found := resolveImport(c, ix, kc, name, from, named, lockPlatforms(below))
			if found {
				add(platform, dep)
				continue
			}
			if len(below) < 2 {
				log.Printf("%s: can't resolve import %s", from, name)
				continue
			}
			// None does (androidx's `-desktop` jars are the JVM's alone, its root modules Android's): each
			// compilation gets the one it has, as Gradle resolves the package per platform.
			for _, leaf := range below {
				dep, found := resolveImport(c, ix, kc, name, from, imp.named(leaf.platform), leaf.lockPlatforms)
				if !found {
					log.Printf("%s: can't resolve import %s on %s", from, name, leaf.platform)
					continue
				}
				add(leaf.platform, dep)
			}
			// The common metadata compile still needs one: the library the common metadata resolves.
			if on := imp.leaves.metadata(); platform == "" && !imp.test && on != "" {
				if dep, found := resolveImport(c, ix, kc, name, from, named, []string{on}); found {
					add(metadataPlatform.name, dep)
				}
			}
		}
	}
	for platform, labels := range imp.keep {
		for _, dep := range labels {
			deps[platform][dep] = true
		}
	}
	// A dependency a source set's parent needs already reaches it.
	for _, platform := range platforms {
		for parent := platform.parent; parent != nil; parent = parent.parent {
			for dep := range deps[platform.name] {
				if deps[parent.name][dep] {
					delete(deps[platform.name], dep)
				}
			}
		}
	}
	for _, platform := range platforms {
		setDeps(r, platform.depsAttr, platform.exportsAttr, deps[platform.name], exported, imp.test)
	}
}

// named is what the directives give a platform's compile, its parents' included: an import of a package several
// libraries declare resolves to one of these.
func (imp imports) named(name string) map[string]bool {
	out := map[string]bool{}
	for _, p := range platforms {
		if p.name != name {
			continue
		}
		for ; p != nil; p = p.parent {
			for _, l := range imp.keep[p.name] {
				out[l] = true
			}
		}
	}
	return out
}

// platform is a group of source sets with one deps attribute; parent is the group every one of its
// compilations also sees.
type platform struct {
	name, depsAttr, exportsAttr string
	parent                      *platform
}

var (
	commonPlatform     = &platform{"", "deps", "exports", nil}
	jvmAndroidPlatform = &platform{"jvmAndroid", "jvm_android_deps", "jvm_android_exports", commonPlatform}
	// On native and the browser every klib dependency is transitive anyway, so their source sets need no exports.
	nonJvmPlatform = &platform{"nonJvm", "non_jvm_deps", "", commonPlatform}
	nativePlatform = &platform{"native", "native_deps", "", nonJvmPlatform}
	webPlatform    = &platform{"web", "web_deps", "", nonJvmPlatform}
	// commonMain's metadata compile alone (a module with native or browser targets has one): what deps can't name.
	metadataPlatform = &platform{"metadata", "metadata_deps", "", nil}
	platforms      = []*platform{
		commonPlatform,
		jvmAndroidPlatform,
		{"jvm", "jvm_deps", "jvm_exports", jvmAndroidPlatform},
		{"android", "android_deps", "android_exports", jvmAndroidPlatform},
		nonJvmPlatform,
		nativePlatform,
		{"linux", "linux_deps", "", nativePlatform},
		{"apple", "apple_deps", "", nativePlatform},
		{"mingw", "mingw_deps", "", nativePlatform},
		webPlatform,
		{"wasmJs", "wasm_js_deps", "", webPlatform},
		{"js", "js_deps", "", webPlatform},
		metadataPlatform,
		// androidDeviceTest is its own compilation: commonTest's dependencies don't reach it.
		{"androidDevice", "device_deps", "", nil},
	}
)

func setDeps(r *rule.Rule, depsAttr, exportsAttr string, deps, exports map[string]bool, test bool) {
	var plain, exported []string
	for dep := range deps {
		if !test && exportsAttr != "" && exports[dep] {
			exported = append(exported, dep)
		} else {
			plain = append(plain, dep)
		}
	}
	sort.Strings(plain)
	sort.Strings(exported)
	if len(plain) > 0 {
		r.SetAttr(depsAttr, plain)
	} else {
		r.DelAttr(depsAttr)
	}
	if exportsAttr != "" {
		if len(exported) > 0 {
			r.SetAttr(exportsAttr, exported)
		} else {
			r.DelAttr(exportsAttr)
		}
	}
}

// leaves are a rule's compilations below its common code: the JVM's, Android's, the native targets' and the
// browser targets'.
type leaves struct {
	android bool
	native  []string
	web     []string
}

// leaf is one group of compilations sharing a deps attribute, and the lockfile platforms they resolve on.
type leaf struct {
	platform      string
	lockPlatforms []string
}

// below is the compilations a source set group's code is part of, and so the platforms its libraries need.
func (l leaves) below(group string) []leaf {
	jvm := leaf{"jvm", []string{"jvm"}}
	android := leaf{"android", []string{"android"}}
	var out []leaf
	nonJvm := func() {
		if len(l.native) > 0 {
			out = append(out, leaf{"native", l.native})
		}
		// Each browser target its own: a library may have only one of them (Skiko's wasm runtime).
		for _, w := range l.web {
			out = append(out, leaf{w, []string{w}})
		}
	}
	switch group {
	case "":
		out = append(out, jvm)
		if l.android {
			out = append(out, android)
		}
		nonJvm()
	case "nonJvm":
		nonJvm()
	case "web":
		for _, w := range l.web {
			out = append(out, leaf{w, []string{w}})
		}
	case "wasmJs", "js":
		out = append(out, leaf{group, []string{group}})
	case "jvmAndroid":
		out = append(out, jvm)
		if l.android {
			out = append(out, android)
		}
	case "jvm":
		out = append(out, jvm)
	case "android", "androidDevice":
		out = append(out, android)
	}
	return out
}

// metadata is the lockfile platform commonMain's metadata compile resolves on, or "" when the rule has none.
func (l leaves) metadata() string {
	switch {
	case len(l.web) > 0:
		return "metadataWeb"
	case len(l.native) > 0:
		return "metadata"
	}
	return ""
}

func lockPlatforms(below []leaf) []string {
	var out []string
	for _, l := range below {
		out = append(out, l.lockPlatforms...)
	}
	return out
}

// resolveImport returns the label an import needs ("" for none), and whether it was understood. `on`, when
// set, limits libraries to those with a variant for every one of those lockfile platforms.
func resolveImport(c *config.Config, ix *resolve.RuleIndex, kc *kmpConfig, name string, from label.Label, named map[string]bool, on []string) (string, bool) {
	for _, pkg := range candidates(name) {
		if lib, ok := kotlinLibraries[pkg]; ok {
			return lib, true
		}
	}
	for _, prefix := range builtin {
		if strings.HasPrefix(name, prefix) {
			return "", true
		}
	}
	for _, pkg := range candidates(name) {
		matches := ix.FindRulesByImportWithConfig(c, resolve.ImportSpec{Lang: languageName, Imp: pkg}, languageName)
		if len(matches) > 0 {
			if len(matches) > 1 {
				log.Printf("%s: package %s is in %d modules; using %s", from, pkg, len(matches), matches[0].Label)
			}
			return moduleLabel(matches[0].Label, from), true
		}
		if lib, ok := kc.maven.find(pkg, named, on); ok {
			if lib == "" {
				return "", true
			}
			return mavenPrefix + lib, true
		}
		if len(kc.maven.byPackage[pkg]) > 0 {
			// The package's libraries just aren't on these platforms; a parent package's would be the wrong one.
			return "", false
		}
	}
	return "", false
}

// moduleLabel writes a module's label the way BUILD files name modules: `//utils/files` for
// //utils/files:files, `:name` within the same package.
func moduleLabel(l label.Label, from label.Label) string {
	l = l.Rel(from.Repo, from.Pkg)
	return l.String()
}
