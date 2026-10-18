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
	platform string // which deps attribute its imports go to: "" (common), "jvm" or "native"
}

var mainSets = []sourceSet{
	{"commonMain", "common_srcs", ""},
	{"jvmAndroidMain", "jvm_android_srcs", "jvm"},
	{"jvmMain", "jvm_srcs", "jvm"},
	{"nativeMain", "native_srcs", "native"},
	{"linuxMain", "linux_srcs", "native"},
	{"appleMain", "apple_srcs", "native"},
	{"mingwMain", "mingw_srcs", "native"},
}

var testSets = []sourceSet{
	{"commonTest", "common_srcs", ""},
	{"jvmAndroidTest", "jvm_android_srcs", "jvm"},
	{"jvmTest", "jvm_srcs", "jvm"},
	{"nativeTest", "native_srcs", "native"},
	{"linuxTest", "linux_srcs", "native"},
	{"appleTest", "apple_srcs", "native"},
	{"mingwTest", "mingw_srcs", "native"},
}

// imports is what GenerateRules hands Resolve for one rule: the imported names per platform.
type imports struct {
	byPlatform map[string][]string // "" | jvm | native → imported names
	packages   []string            // the module's own packages (not dependencies)
	test       bool
	associate  string // the kmp_library under test, for a kmp_test
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

	main, mainSetsPresent := scan(args.Dir, mainSets)
	sqldelightRoot := filepath.Join(args.Dir, "src", "commonMain", "sqldelight")
	hasSqldelight := dirHasFiles(sqldelightRoot, ".sq")
	if len(mainSetsPresent) == 0 && !hasSqldelight {
		return res
	}

	plugins := union(main.plugins, kc.plugins)
	delete(plugins, "mokkery")

	lib := rule.NewRule("kmp_library", name)
	for _, set := range mainSetsPresent {
		lib.SetAttr(set.srcsAttr, glob(set.dir))
	}
	if len(kc.targets) > 0 {
		lib.SetAttr("native_targets", kc.targets)
	}
	if len(plugins) > 0 {
		lib.SetAttr("plugins", sorted(plugins))
	}
	lib.SetAttr("visibility", []string{"//visibility:public"})
	ownPackages := sortedKeys(main.packages)

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
	res.Imports = append(res.Imports, imports{byPlatform: keys(main.imports), packages: ownPackages})

	tests, testSetsPresent := scan(args.Dir, testSets)
	if len(testSetsPresent) > 0 {
		t := rule.NewRule("kmp_test", "test")
		t.SetAttr("associate", ":"+name)
		for _, set := range testSetsPresent {
			t.SetAttr(set.srcsAttr, glob(set.dir))
		}
		if len(kc.targets) > 0 {
			t.SetAttr("native_targets", kc.targets)
		}
		testPlugins := union(plugins, nil)
		for p := range tests.plugins {
			testPlugins[p] = true
		}
		if len(testPlugins) > 0 {
			t.SetAttr("plugins", sorted(testPlugins))
		}
		res.Gen = append(res.Gen, t)
		res.Imports = append(res.Imports, imports{byPlatform: keys(tests.imports), test: true, associate: name})
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
