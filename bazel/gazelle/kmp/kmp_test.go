package kmp

import (
	"os"
	"path/filepath"
	"reflect"
	"testing"
)

func TestCandidates(t *testing.T) {
	got := candidates("com.moneymanager.domain.model.Money.Companion")
	want := []string{
		"com.moneymanager.domain.model.Money.Companion",
		"com.moneymanager.domain.model.Money",
		"com.moneymanager.domain.model",
		"com.moneymanager.domain",
		"com.moneymanager",
		"com",
	}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("candidates = %v, want %v", got, want)
	}
	if got := candidates("kotlinx.coroutines.flow.*")[0]; got != "kotlinx.coroutines.flow" {
		t.Errorf("a wildcard import's first candidate is %s", got)
	}
}

func TestScanFile(t *testing.T) {
	file := filepath.Join(t.TempDir(), "A.kt")
	src := "package com.example.`data`\n\nimport kotlinx.serialization.Serializable\nimport kotlinx.coroutines.flow.*\nimport tech.mappie.api.ObjectMappie as Mappie\n\n@Serializable\nclass A\n"
	if err := os.WriteFile(file, []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}
	s := &scanned{packages: map[string]bool{}, imports: map[string]map[string]bool{}, plugins: map[string]bool{}}
	scanFile(file, "", s)
	if !s.packages["com.example.data"] {
		t.Errorf("packages = %v", s.packages)
	}
	wantImports := []string{"kotlinx.coroutines.flow.*", "kotlinx.serialization.Serializable", "tech.mappie.api.ObjectMappie"}
	if got := sortedKeys(s.imports[""]); !reflect.DeepEqual(got, wantImports) {
		t.Errorf("imports = %v, want %v", got, wantImports)
	}
	if got := sortedKeys(s.plugins); !reflect.DeepEqual(got, []string{"mappie", "serialization"}) {
		t.Errorf("plugins = %v", got)
	}
}

func TestMavenIndexPrefersTheSpecificLibrary(t *testing.T) {
	lock := filepath.Join(t.TempDir(), "lock.json")
	data := `{"names": {"g:core": "core", "g:fat": "fat", "g:api": "api"},
	          "packages": {"g:core": ["a.b"], "g:fat": ["a.b", "c", "d"], "g:api": ["e"]},
	          "artifacts": {"g:api": {"jvm": {"deps": ["g:fat", "g:core"]}}}}`
	if err := os.WriteFile(lock, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	idx := loadMavenIndex(lock)
	if got, _ := idx.find("a.b", nil); got != "core" {
		t.Errorf("find(a.b) = %s, want core", got)
	}
	// A library a directive already names wins: it reaches the specific one through its exports.
	if got, _ := idx.find("a.b", map[string]bool{"@maven_kmp//:fat": true}); got != "fat" {
		t.Errorf("find(a.b) with fat named = %s, want fat", got)
	}
	// One a named library's api already brings, every declaring library of it, needs no dependency of its own.
	if got, ok := idx.find("a.b", map[string]bool{"@maven_kmp//:api": true}); got != "" || !ok {
		t.Errorf("find(a.b) with api named = %q, %v; want \"\", true", got, ok)
	}
	// Of several, the one whose api brings the others covers the import (runtime over runtime-annotation).
	data = `{"names": {"g:ann": "ann", "g:rt": "rt"}, "packages": {"g:ann": ["x"], "g:rt": ["x", "y"]},
	          "artifacts": {"g:rt": {"jvm": {"deps": ["g:ann"]}}}}`
	if err := os.WriteFile(lock, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	if got, _ := loadMavenIndex(lock).find("x", nil); got != "rt" {
		t.Errorf("find(x) = %s, want rt", got)
	}
}
