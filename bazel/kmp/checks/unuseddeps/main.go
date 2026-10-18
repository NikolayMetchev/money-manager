// Command unuseddeps fails when a module declares a dependency its JVM compile never used (kmp_unused_deps,
// #1016): the unused half of DAGP's buildHealth. The compile's jdeps file (rules_kotlin's
// `blaze_deps.Dependencies` proto) says which classpath jars it read; a dependency is used when any of its own
// jars is one of them. What a dependency only re-exports doesn't count for it: depend on that directly.
package main

import (
	"bufio"
	"errors"
	"flag"
	"fmt"
	"os"
	"sort"
	"strings"
)

func main() {
	jdeps := flag.String("jdeps", "", "the compile's jdeps file")
	depsFile := flag.String("deps", "", "one line per declared dependency: label, then its own jars, tab-separated")
	module := flag.String("module", "", "the module, for the message")
	out := flag.String("out", "", "stamp file written on success")
	flag.Parse()
	used, err := usedJars(*jdeps)
	if err != nil {
		fail(err)
	}
	declared, err := readDeps(*depsFile)
	if err != nil {
		fail(err)
	}
	if unused := unusedDeps(declared, used); len(unused) > 0 {
		fmt.Fprintf(os.Stderr, "%s declares dependencies its compile never uses:\n  %s\n"+
			"Remove the import or directive that brings each one; one that's only needed at run time (a Metro "+
			"contributor, an engine found by discovery) is a `# gazelle:kmp_keep_dep`.\n",
			*module, strings.Join(unused, "\n  "))
		os.Exit(1)
	}
	if err := os.WriteFile(*out, nil, 0o644); err != nil {
		fail(err)
	}
}

func fail(err error) {
	fmt.Fprintln(os.Stderr, err)
	os.Exit(2)
}

type dep struct {
	label string
	jars  []string
}

func readDeps(path string) ([]dep, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var deps []dep
	s := bufio.NewScanner(f)
	for s.Scan() {
		if fields := strings.Split(s.Text(), "\t"); fields[0] != "" {
			deps = append(deps, dep{label: fields[0], jars: fields[1:]})
		}
	}
	return deps, s.Err()
}

func unusedDeps(declared []dep, used map[string]bool) []string {
	var unused []string
	for _, d := range declared {
		found := false
		for _, jar := range d.jars {
			found = found || used[jar]
		}
		if !found {
			unused = append(unused, d.label)
		}
	}
	sort.Strings(unused)
	return unused
}

// The jdeps Kind values a compile reads: EXPLICIT, IMPLICIT and INCOMPLETE (only UNUSED, 2, isn't).
const kindUnused = 2

// usedJars decodes the jdeps proto by hand (Dependencies.dependency = 1 {path = 1, kind = 2}), which needs no
// protobuf library for two fields.
func usedJars(path string) (map[string]bool, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	used := map[string]bool{}
	err = fields(data, func(num int, value []byte, _ uint64) error {
		if num != 1 {
			return nil
		}
		var jar string
		kind := uint64(0)
		if err := fields(value, func(num int, value []byte, n uint64) error {
			switch num {
			case 1:
				jar = string(value)
			case 2:
				kind = n
			}
			return nil
		}); err != nil {
			return err
		}
		if kind != kindUnused {
			used[jar] = true
		}
		return nil
	})
	return used, err
}

// fields walks a protobuf message's top-level fields, handing over varints as n and length-delimited ones as
// value.
func fields(b []byte, visit func(num int, value []byte, n uint64) error) error {
	for len(b) > 0 {
		key, rest, err := varint(b)
		if err != nil {
			return err
		}
		b = rest
		num, wire := int(key>>3), key&7
		switch wire {
		case 0:
			n, rest, err := varint(b)
			if err != nil {
				return err
			}
			b = rest
			if err := visit(num, nil, n); err != nil {
				return err
			}
		case 2:
			n, rest, err := varint(b)
			if err != nil {
				return err
			}
			if uint64(len(rest)) < n {
				return errors.New("jdeps: truncated field")
			}
			if err := visit(num, rest[:n], 0); err != nil {
				return err
			}
			b = rest[n:]
		default:
			return fmt.Errorf("jdeps: unexpected wire type %d", wire)
		}
	}
	return nil
}

func varint(b []byte) (uint64, []byte, error) {
	var n uint64
	for i := 0; i < len(b) && i < 10; i++ {
		n |= uint64(b[i]&0x7f) << (7 * i)
		if b[i] < 0x80 {
			return n, b[i+1:], nil
		}
	}
	return 0, nil, errors.New("jdeps: bad varint")
}
