package kmp

import (
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"
)

// One package, one module (CLAUDE.md, "Packages"): a package the main sources of two modules declare lets
// `internal` leak across the module boundary and hides which module owns a type. Gazelle sees every module's
// packages, so it fails the run (and CI's `--mode=diff`) when two declare the same one. Test source sets
// compile separately and may share packages.
var (
	ownersMu sync.Mutex
	owners   = map[string][]string{} // package → module directories
)

// Modules with a kmp_visibility, by label: tests reach them through `:<name>_for_tests`, which only a
// testonly target may use, as Gradle's verifyNoWriteRepositoryUsage only reads main sources.
var (
	restrictedMu sync.Mutex
	restricted   = map[string]string{}
)

func recordRestricted(rel, name string) {
	restrictedMu.Lock()
	defer restrictedMu.Unlock()
	restricted["//"+rel] = "//" + rel + ":" + name + "_for_tests"
}

func forTests(dep string) string {
	restrictedMu.Lock()
	defer restrictedMu.Unlock()
	if t, ok := restricted[dep]; ok {
		return t
	}
	return dep
}

func recordPackages(rel string, packages []string) {
	ownersMu.Lock()
	defer ownersMu.Unlock()
	for _, p := range packages {
		owners[p] = append(owners[p], rel)
	}
}

// splitPackages describes every package more than one module declares, or "" when there is none.
func splitPackages() string {
	ownersMu.Lock()
	defer ownersMu.Unlock()
	var split []string
	for p, modules := range owners {
		if len(modules) > 1 {
			split = append(split, p)
		}
	}
	if len(split) == 0 {
		return ""
	}
	sort.Strings(split)
	var b strings.Builder
	b.WriteString("A Kotlin package must belong to exactly one module (CLAUDE.md, \"Packages\"), but these are split:\n")
	for _, p := range split {
		modules := append([]string(nil), owners[p]...)
		sort.Strings(modules)
		fmt.Fprintf(&b, "  %s\n", p)
		for _, m := range modules {
			fmt.Fprintf(&b, "      %s\n", m)
		}
	}
	b.WriteString("Give each module its own package (e.g. append the module name as a sub-package).\n")
	return b.String()
}

// DoneGeneratingRules runs once every directory has been visited.
func (*kmpLang) DoneGeneratingRules() {
	if msg := splitPackages(); msg != "" {
		fmt.Fprint(os.Stderr, msg)
		os.Exit(1)
	}
}
