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
	// The module itself and, for tests, the module under test, written as moduleLabel writes them.
	self := map[string]bool{moduleLabel(from, from): true}
	if imp.associate != "" {
		self[moduleLabel(label.New("", from.Pkg, imp.associate), from)] = true
	}
	deps := map[string]map[string]bool{"": {}, "jvm": {}, "native": {}}
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
	// A dependency common code needs already reaches every platform.
	for _, platform := range []string{"jvm", "native"} {
		for dep := range deps[platform] {
			if deps[""][dep] {
				delete(deps[platform], dep)
			}
		}
	}
	setDeps(r, "deps", "exports", deps[""], kc, imp.test)
	setDeps(r, "jvm_deps", "jvm_exports", deps["jvm"], kc, imp.test)
	setDeps(r, "native_deps", "", deps["native"], kc, imp.test)
}

func setDeps(r *rule.Rule, depsAttr, exportsAttr string, deps map[string]bool, kc *kmpConfig, test bool) {
	var plain, exported []string
	for dep := range deps {
		if !test && exportsAttr != "" && kc.exports[dep] {
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
