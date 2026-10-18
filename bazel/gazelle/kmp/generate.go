package kmp

import (
	"bufio"
	"log"
	"os"
	"path"
	"path/filepath"
	"regexp"
	"sort"
	"strings"

	"github.com/bazelbuild/bazel-gazelle/language"
	"github.com/bazelbuild/bazel-gazelle/rule"
)

// sourceSet is one `src/<name>/kotlin` directory and the kmp_library/kmp_test attribute it feeds.
type sourceSet struct {
	dir      string // commonMain
	srcsAttr string // common_srcs
	platform string // which deps attribute its imports go to: "" (common), "jvmAndroid", "jvm", "android", "native" or a family
}

var mainSets = []sourceSet{
	{"commonMain", "common_srcs", ""},
	{"jvmAndroidMain", "jvm_android_srcs", "jvmAndroid"},
	{"jvmMain", "jvm_srcs", "jvm"},
	{"androidMain", "android_srcs", "android"},
	{"nativeMain", "native_srcs", "native"},
	{"linuxMain", "linux_srcs", "linux"},
	{"appleMain", "apple_srcs", "apple"},
	{"mingwMain", "mingw_srcs", "mingw"},
	// A kotlin("jvm") module's only compilation is the JVM leaf.
	{"main", "jvm_srcs", "jvm"},
}

var testSets = []sourceSet{
	{"commonTest", "common_srcs", ""},
	{"jvmAndroidTest", "jvm_android_srcs", "jvmAndroid"},
	{"jvmTest", "jvm_srcs", "jvm"},
	{"androidHostTest", "android_srcs", "android"},
	{"nativeTest", "native_srcs", "native"},
	{"linuxTest", "linux_srcs", "linux"},
	{"appleTest", "apple_srcs", "apple"},
	{"mingwTest", "mingw_srcs", "mingw"},
	{"test", "jvm_srcs", "jvm"},
}

// imports is what GenerateRules hands Resolve for one rule: the imported names per platform.
type imports struct {
	byPlatform map[string][]string // "" | jvmAndroid | jvm | android | native | linux | apple | mingw → imported names
	keep       map[string][]string // platform → labels kept by kmp_keep_dep
	packages   []string            // the module's own packages (not dependencies)
	test       bool
	associate  string // the kmp_library under test, for a kmp_test
	leaves     leaves // the compilations the rule has, for libraries some platforms lack
}

var (
	packageLine = regexp.MustCompile("^package\\s+([\\w.`]+)")
	importLine  = regexp.MustCompile("^import\\s+([\\w.`]+?)(\\.\\*)?(\\s+as\\s+\\w+)?\\s*;?\\s*$")
)

// scanned is what a module's sources say about it.
type scanned struct {
	packages map[string]bool
	imports  map[string]map[string]bool // platform → names
	plugins  map[string]bool
}

// pluginSignals infer a compiler plugin from what the sources use, so modules need no directive for it.
var pluginSignals = []struct {
	plugin string
	match  func(line string) bool
}{
	{"serialization", func(l string) bool { return strings.Contains(l, "@Serializable") }},
	{"compose", func(l string) bool { return strings.Contains(l, "@Composable") }},
	{"metro", func(l string) bool { return strings.HasPrefix(l, "import dev.zacsweers.metro.") }},
	{"mappie", func(l string) bool { return strings.HasPrefix(l, "import tech.mappie.") }},
	{"mokkery", func(l string) bool { return strings.HasPrefix(l, "import dev.mokkery.") }},
}

func scan(dir string, sets []sourceSet) (*scanned, []sourceSet) {
	s := &scanned{packages: map[string]bool{}, imports: map[string]map[string]bool{}, plugins: map[string]bool{}}
	var present []sourceSet
	for _, set := range sets {
		root := filepath.Join(dir, "src", set.dir, "kotlin")
		found := false
		_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
			if err != nil || d.IsDir() || !strings.HasSuffix(p, ".kt") {
				return nil
			}
			found = true
			scanFile(p, set.platform, s)
			return nil
		})
		if found {
			present = append(present, set)
		}
	}
	return s, present
}

func scanFile(file, platform string, s *scanned) {
	f, err := os.Open(file)
	if err != nil {
		return
	}
	defer f.Close()
	if s.imports[platform] == nil {
		s.imports[platform] = map[string]bool{}
	}
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1024*1024), 1024*1024)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		for _, sig := range pluginSignals {
			if sig.match(line) {
				s.plugins[sig.plugin] = true
			}
		}
		if m := packageLine.FindStringSubmatch(line); m != nil {
			s.packages[strings.ReplaceAll(m[1], "`", "")] = true
		} else if m := importLine.FindStringSubmatch(line); m != nil {
			name := strings.ReplaceAll(m[1], "`", "")
			if m[2] != "" {
				name += ".*"
			}
			s.imports[platform][name] = true
		}
	}
}

func (*kmpLang) GenerateRules(args language.GenerateArgs) language.GenerateResult {
	kc := getConfig(args.Config)
	var res language.GenerateResult
	if !kc.generate {
		return res
	}
	name := path.Base(args.Rel)
	if args.Rel == "" {
		return res
	}

	sets := mainSets
	if !kc.android {
		// Without the Android target Gradle compiles no androidMain.
		sets = withoutAndroid(sets)
	}
	main, mainSetsPresent := scan(args.Dir, sets)
	sqldelightRoot := filepath.Join(args.Dir, "src", "commonMain", "sqldelight")
	hasSqldelight := dirHasFiles(sqldelightRoot, ".sq")
	if len(mainSetsPresent) == 0 && !hasSqldelight && len(kc.cinterops) == 0 {
		return res
	}

	plugins := union(main.plugins, kc.plugins)
	delete(plugins, "mokkery")

	lib := rule.NewRule("kmp_library", name)
	for _, set := range mainSetsPresent {
		lib.SetAttr(set.srcsAttr, glob(set.dir))
	}
	if len(kc.generatedSrcs) > 0 {
		lib.SetAttr("generated_srcs", kc.generatedSrcs)
	}
	if len(kc.cinterops) > 0 {
		lib.SetAttr("cinterops", kc.cinterops)
	}
	if kc.android {
		lib.SetAttr("android", true)
	}
	if len(kc.targets) > 0 {
		lib.SetAttr("native_targets", kc.targets)
	}
	if len(plugins) > 0 {
		lib.SetAttr("plugins", sorted(plugins))
	}
	if len(kc.visibility) > 0 {
		recordRestricted(args.Rel, name)
		lib.SetAttr("visibility", kc.visibility)
	} else {
		lib.SetAttr("visibility", []string{"//visibility:public"})
	}
	if len(kc.forbidDeps) > 0 {
		lib.SetAttr("forbidden_deps", kc.forbidDeps)
	}
	if kept := kc.keptLabels(false); len(kept) > 0 {
		lib.SetAttr("keep_deps", kept)
	}
	if res, ok := resourceGlob(args.Dir, "Main"); ok {
		lib.SetAttr("resources", res)
	}
	if kc.forbidReference != "" {
		lib.SetAttr("forbidden_reference", kc.forbidReference)
		lib.SetAttr("forbidden_reference_reason", kc.referenceReason)
		if len(kc.allowReference) > 0 {
			lib.SetAttr("forbidden_reference_allowed", kc.allowReference)
		}
	}
	ownPackages := sortedKeys(main.packages)
	recordPackages(args.Rel, ownPackages)

	if hasSqldelight {
		if kc.sqldelightPackage == "" {
			log.Printf("%s: has .sq files but no `# gazelle:%s` directive", args.Rel, sqldelightPackageDirective)
		}
		sq := rule.NewRule("sqldelight_library", "sqldelight")
		sq.SetAttr("srcs", rule.GlobValue{Patterns: []string{"src/commonMain/sqldelight/**/*.sq"}})
		sq.SetAttr("module_name", name)
		if kc.sqldelightPackage != "" {
			sq.SetAttr("package", kc.sqldelightPackage)
			ownPackages = append(ownPackages, kc.sqldelightPackage)
		}
		if len(kc.sqldelightDeps) > 0 {
			sq.SetAttr("deps", kc.sqldelightDeps)
		}
		sq.SetAttr("visibility", []string{"//visibility:public"})
		lib.SetAttr("sqldelight", ":sqldelight")
		res.Gen = append(res.Gen, sq)
		res.Imports = append(res.Imports, imports{})
	}
	lib.SetPrivateAttr(packagesKey, ownPackages)
	res.Gen = append(res.Gen, lib)
	res.Imports = append(res.Imports, imports{byPlatform: keys(main.imports), keep: kc.kept(false), packages: ownPackages, leaves: leaves{android: kc.android, native: kc.targets}})

	tSets := testSets
	if !kc.android {
		tSets = withoutAndroid(tSets)
	}
	tests, testSetsPresent := scan(args.Dir, tSets)
	if len(testSetsPresent) > 0 {
		t := rule.NewRule("kmp_test", "test")
		t.SetAttr("associate", ":"+name)
		for _, set := range testSetsPresent {
			t.SetAttr(set.srcsAttr, glob(set.dir))
		}
		// Tests that are all JVM (jvmTest only) have no native test binary, as KGP's native test tasks find no
		// sources.
		if len(kc.targets) > 0 && runsOnNative(testSetsPresent) {
			t.SetAttr("native_targets", kc.targets)
		}
		hostTests := kc.android && !kc.noHostTests && runsOnAndroid(testSetsPresent)
		if hostTests {
			t.SetAttr("android", true)
		}
		testPlugins := union(plugins, nil)
		for p := range tests.plugins {
			testPlugins[p] = true
		}
		if len(testPlugins) > 0 {
			t.SetAttr("plugins", sorted(testPlugins))
		}
		if len(kc.testData) > 0 {
			t.SetAttr("data", kc.testData)
		}
		if kc.testSize != "" {
			t.SetAttr("size", kc.testSize)
		}
		if len(kc.testJvmFlags) > 0 {
			t.SetAttr("jvm_flags", kc.testJvmFlags)
		}
		if kc.testFlaky {
			t.SetAttr("flaky", true)
		}
		if len(kc.testJvmTags) > 0 {
			t.SetAttr("jvm_tags", kc.testJvmTags)
		}
		if res, ok := resourceGlob(args.Dir, "Test"); ok {
			t.SetAttr("resources", res)
		}
		if len(kc.forbidDeps) > 0 {
			t.SetAttr("forbidden_deps", kc.forbidDeps)
		}
		if kept := kc.keptLabels(true); len(kept) > 0 {
			t.SetAttr("keep_deps", kept)
		}
		res.Gen = append(res.Gen, t)
		testLeaves := leaves{android: hostTests}
		if runsOnNative(testSetsPresent) {
			testLeaves.native = kc.targets
		}
		res.Imports = append(res.Imports, imports{byPlatform: keys(tests.imports), keep: kc.kept(true), test: true, associate: name, leaves: testLeaves})
	}
	return res
}

func glob(set string) rule.GlobValue {
	return rule.GlobValue{Patterns: []string{"src/" + set + "/kotlin/**/*.kt"}}
}

func dirHasFiles(root, suffix string) bool {
	found := false
	_ = filepath.WalkDir(root, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() && strings.HasSuffix(p, suffix) {
			found = true
			return filepath.SkipAll
		}
		return nil
	})
	return found
}

func keys(m map[string]map[string]bool) map[string][]string {
	out := map[string][]string{}
	for k, v := range m {
		out[k] = sortedKeys(v)
	}
	return out
}

func sortedKeys(m map[string]bool) []string {
	var out []string
	for k := range m {
		out = append(out, k)
	}
	sort.Strings(out)
	return out
}

func union(a map[string]bool, b []string) map[string]bool {
	out := map[string]bool{}
	for k := range a {
		out[k] = true
	}
	for _, k := range b {
		out[k] = true
	}
	return out
}

func sorted(m map[string]bool) []string { return sortedKeys(m) }

// kept is the dependencies directives add, import or not, per platform: kmp_keep_dep's and (for a library)
// kmp_export's.
func (c *kmpConfig) kept(test bool) map[string][]string {
	out := map[string][]string{}
	all := c.keepDeps
	if !test {
		all = append(append([]keepDep(nil), all...), c.exports...)
	}
	for _, d := range all {
		if d.test == test {
			out[d.platform] = append(out[d.platform], d.label)
		}
	}
	return out
}

// keptLabels is kmp_keep_dep's labels, once each, for the rule's keep_deps: no import explains them, so the
// unused-deps check (//bazel/kmp/checks) leaves them alone.
func (c *kmpConfig) keptLabels(test bool) []string {
	labels := map[string]bool{}
	for _, d := range c.keepDeps {
		if d.test == test {
			labels[d.label] = true
		}
	}
	return sortedKeys(labels)
}

func (c *kmpConfig) exported() map[string]bool {
	out := map[string]bool{}
	for _, d := range c.exports {
		out[d.label] = true
	}
	return out
}

// runsOnAndroid is whether any of the tests' source sets is in the Android host tests' compilation.
func runsOnAndroid(sets []sourceSet) bool {
	for _, set := range sets {
		if set.platform == "" || set.platform == "jvmAndroid" || set.platform == "android" {
			return true
		}
	}
	return false
}

func runsOnNative(sets []sourceSet) bool {
	for _, set := range sets {
		if set.platform != "jvm" && set.platform != "jvmAndroid" && set.platform != "android" {
			return true
		}
	}
	return false
}

// resourceGlob is the JVM compilation's `resources` dirs that exist (`kind` is Main or Test), or nil: the
// classpath resources Gradle's processResources merges.
func resourceGlob(dir, kind string) (rule.GlobValue, bool) {
	var patterns []string
	for _, set := range []string{"common" + kind, "jvmAndroid" + kind, "jvm" + kind, strings.ToLower(kind)} {
		rel := "src/" + set + "/resources"
		if dirHasFiles(filepath.Join(dir, filepath.FromSlash(rel)), "") {
			patterns = append(patterns, rel+"/**")
		}
	}
	return rule.GlobValue{Patterns: patterns}, patterns != nil
}

func withoutAndroid(sets []sourceSet) []sourceSet {
	var out []sourceSet
	for _, set := range sets {
		if set.platform != "android" {
			out = append(out, set)
		}
	}
	return out
}
