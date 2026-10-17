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

// Libraries the toolchain and the distribution provide: no dependency to add.
var builtin = []string{"kotlin.", "java.", "javax.", "kotlinx.cinterop.", "platform."}

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
		self[moduleLabel(label.New("", from.Pkg, imp.associate), from)] = true
	}
	deps := map[string]map[string]bool{}
	for _, platform := range platforms {
		deps[platform.name] = map[string]bool{}
	}
	for platform, names := range imp.byPlatform {
		for _, name := range names {
			dep, found := resolveImport(c, ix, kc, name, from)
			if !found {
				log.Printf("%s: can't resolve import %s", from, name)
				continue
			}
			if dep != "" && !self[dep] {
				deps[platform][dep] = true
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

// platform is a group of source sets with one deps attribute; parent is the group every one of its
// compilations also sees.
type platform struct {
	name, depsAttr, exportsAttr string
	parent                      *platform
}

var (
	commonPlatform = &platform{"", "deps", "exports", nil}
	nativePlatform = &platform{"native", "native_deps", "", commonPlatform}
	platforms      = []*platform{
		commonPlatform,
		{"jvm", "jvm_deps", "jvm_exports", commonPlatform},
		nativePlatform,
		// On native every klib dependency is transitive anyway, so the family source sets need no exports.
		{"linux", "linux_deps", "", nativePlatform},
		{"apple", "apple_deps", "", nativePlatform},
		{"mingw", "mingw_deps", "", nativePlatform},
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

// resolveImport returns the label an import needs ("" for none), and whether it was understood.
func resolveImport(c *config.Config, ix *resolve.RuleIndex, kc *kmpConfig, name string, from label.Label) (string, bool) {
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
		if lib, ok := kc.maven.find(pkg); ok {
			return "@maven_kmp//:" + lib, true
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
