package main

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

// dependency builds one jdeps Dependency entry: path = 1, kind = 2.
func dependency(path string, kind byte) []byte {
	inner := append([]byte{0x0a, byte(len(path))}, path...)
	inner = append(inner, 0x10, kind)
	return append([]byte{0x0a, byte(len(inner))}, inner...)
}

func TestUnusedDepsCountOnlyTheirOwnJars(t *testing.T) {
	jdeps := filepath.Join(t.TempDir(), "a.jdeps")
	var data []byte
	data = append(data, dependency("bin/core.abi.jar", 0)...)
	data = append(data, dependency("bin/bigdecimal.abi.jar", kindUnused)...)
	data = append(data, dependency("ext/json.jar", 1)...)
	data = append(data, 0x12, 3, '/', '/', 'a', 0x18, 1) // rule_label, success
	if err := os.WriteFile(jdeps, data, 0o644); err != nil {
		t.Fatal(err)
	}
	used, err := usedJars(jdeps)
	if err != nil {
		t.Fatal(err)
	}
	declared := []dep{
		{"//app/model/core", []string{"bin/core.abi.jar"}},
		{"//utils/bigdecimal", []string{"bin/bigdecimal.abi.jar"}},
		{"@maven_kmp//:json", []string{"ext/json.jar"}},
		{"//utils/files", []string{"bin/files.abi.jar"}},
	}
	if got, want := unusedDeps(declared, used), []string{"//utils/bigdecimal", "//utils/files"}; !reflect.DeepEqual(got, want) {
		t.Errorf("unused %q, want %q", got, want)
	}
}
