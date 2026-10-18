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
	if got, _ := idx.find("a.b", nil, nil); got != "core" {
		t.Errorf("find(a.b) = %s, want core", got)
	}
	// A library a directive already names wins: it reaches the specific one through its exports.
	if got, _ := idx.find("a.b", map[string]bool{"@maven_kmp//:fat": true}, nil); got != "fat" {
		t.Errorf("find(a.b) with fat named = %s, want fat", got)
	}
	// One a named library's api already brings, every declaring library of it, needs no dependency of its own.
	if got, ok := idx.find("a.b", map[string]bool{"@maven_kmp//:api": true}, nil); got != "" || !ok {
		t.Errorf("find(a.b) with api named = %q, %v; want \"\", true", got, ok)
	}
	// Of several, the one whose api brings the others covers the import (runtime over runtime-annotation).
	data = `{"names": {"g:ann": "ann", "g:rt": "rt"}, "packages": {"g:ann": ["x"], "g:rt": ["x", "y"]},
	          "artifacts": {"g:rt": {"jvm": {"deps": ["g:ann"]}}}}`
	if err := os.WriteFile(lock, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	if got, _ := loadMavenIndex(lock).find("x", nil, nil); got != "rt" {
		t.Errorf("find(x) = %s, want rt", got)
	}
}

func TestMavenIndexResolvesPerPlatform(t *testing.T) {
	lock := filepath.Join(t.TempDir(), "lock.json")
	// androidx's runtime-desktop is only the JVM's; its root module has an Android variant too.
	data := `{"names": {"g:desktop": "desktop", "g:root": "root"},
	          "packages": {"g:desktop": ["p"], "g:root": ["p", "q", "r"]},
	          "artifacts": {"g:desktop": {"jvm": {}}, "g:root": {"jvm": {}, "android": {}}}}`
	if err := os.WriteFile(lock, []byte(data), 0o644); err != nil {
		t.Fatal(err)
	}
	idx := loadMavenIndex(lock)
	if got, _ := idx.find("p", nil, nil); got != "desktop" {
		t.Errorf("find(p) = %s, want desktop", got)
	}
	if got, _ := idx.find("p", nil, lockPlatforms(leaves{android: true}.below(""))); got != "root" {
		t.Errorf("find(p) on jvm and android = %s, want root", got)
	}
	if got, _ := idx.find("p", nil, []string{"android"}); got != "root" {
		t.Errorf("find(p) on android = %s, want root", got)
	}
	if _, ok := idx.find("p", nil, []string{"linuxX64"}); ok {
		t.Errorf("find(p) on linuxX64 found a library")
	}
}
