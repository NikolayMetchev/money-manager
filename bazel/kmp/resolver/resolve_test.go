package main

import (
	"os"
	"path/filepath"
	"testing"
)

func TestCompareVersions(t *testing.T) {
	ordered := []string{"1.0-dev1", "1.0-alpha01", "1.0-beta2", "1.0-rc1", "1.0", "1.0.1", "1.2", "1.10", "2.0.0-RC", "2.0.0"}
	for i := 0; i+1 < len(ordered); i++ {
		if c := CompareVersions(ordered[i], ordered[i+1]); c >= 0 {
			t.Errorf("CompareVersions(%s, %s) = %d, want < 0", ordered[i], ordered[i+1], c)
		}
		if c := CompareVersions(ordered[i+1], ordered[i]); c <= 0 {
			t.Errorf("CompareVersions(%s, %s) = %d, want > 0", ordered[i+1], ordered[i], c)
		}
	}
	if c := CompareVersions("1.11.0", "1.11.0"); c != 0 {
		t.Errorf("equal versions compare %d", c)
	}
}

func TestPickVariant(t *testing.T) {
	variants := []Variant{
		{Name: "metadataApiElements", Attributes: attrs{"org.gradle.usage": "kotlin-metadata", "org.jetbrains.kotlin.platform.type": "common"}},
		{Name: "jvmApiElements-published", Attributes: attrs{"org.gradle.usage": "java-api", "org.gradle.category": "library", "org.jetbrains.kotlin.platform.type": "jvm", "org.gradle.jvm.environment": "standard-jvm"}},
		{Name: "jvmRuntimeElements-published", Attributes: attrs{"org.gradle.usage": "java-runtime", "org.gradle.category": "library", "org.jetbrains.kotlin.platform.type": "jvm"}},
		{Name: "androidReleaseApiElements", Attributes: attrs{"org.gradle.usage": "java-api", "org.gradle.category": "library", "org.jetbrains.kotlin.platform.type": "androidJvm"}},
		{Name: "linuxX64ApiElements-published", Attributes: attrs{"org.gradle.usage": "kotlin-api", "org.gradle.category": "library", "org.jetbrains.kotlin.platform.type": "native", "org.jetbrains.kotlin.native.target": "linux_x64"}},
		{Name: "linuxX64SourcesElements", Attributes: attrs{"org.gradle.usage": "kotlin-runtime", "org.gradle.category": "documentation", "org.jetbrains.kotlin.platform.type": "native", "org.jetbrains.kotlin.native.target": "linux_x64"}},
	}
	cases := []struct {
		platform, usage, want string
	}{
		{"jvm", "java-api", "jvmApiElements-published"},
		{"jvm", "java-runtime", "jvmRuntimeElements-published"},
		{"linuxX64", "kotlin-api", "linuxX64ApiElements-published"},
		{"mingwX64", "kotlin-api", ""},
	}
	for _, c := range cases {
		got := pick(platforms[c.platform], c.usage, variants)
		name := ""
		if got != nil {
			name = got.Name
		}
		if name != c.want {
			t.Errorf("pick(%s, %s) = %q, want %q", c.platform, c.usage, name, c.want)
		}
	}
}

func TestPickVariantRejectsNewerJvm(t *testing.T) {
	variants := []Variant{{Name: "jdk99", Attributes: attrs{"org.gradle.usage": "java-api", "org.gradle.jvm.version": float64(99)}}}
	if got := pick(platforms["jvm"], "java-api", variants); got != nil {
		t.Errorf("picked %s for JVM %d", got.Name, jvmVersion)
	}
}

func TestReadCatalog(t *testing.T) {
	path := filepath.Join(t.TempDir(), "libs.versions.toml")
	toml := `# comment
[versions]
kotlinx-io = "0.9.1" # trailing
odd = "a#b"

[libraries]
kotlinx-io-core = { module = "org.jetbrains.kotlinx:kotlinx-io-core", version.ref = "kotlinx-io" }
odd = { group = "g", name = "odd", version.ref = "odd" }
fixed = { module = "g:fixed", version = "1.0" }

[plugins]
kotlin = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlinx-io" }
`
	if err := os.WriteFile(path, []byte(toml), 0o644); err != nil {
		t.Fatal(err)
	}
	cat, err := ReadCatalog(path)
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]string{
		"kotlinx-io-core": "org.jetbrains.kotlinx:kotlinx-io-core:0.9.1",
		"odd":             "g:odd:a#b",
		"fixed":           "g:fixed:1.0",
	}
	for alias, coordinate := range want {
		if got := cat.Libraries[alias].String(); got != coordinate {
			t.Errorf("%s = %s, want %s", alias, got, coordinate)
		}
	}
}

// A BOM's constraints and a library's own dependencyConstraints raise the versions of what is depended on,
// as Gradle's do, but neither brings a module in, and the BOM is no library.
func TestConstraintsRaiseVersionsButAddNothing(t *testing.T) {
	files := map[string][]byte{}
	library := func(artifact, version, variant string) {
		path, body := pom("g", artifact, version, "")
		files[path] = body
		files["/g/"+artifact+"/"+version+"/"+artifact+"-"+version+".module"] = []byte(`{"variants": [` + variant + `]}`)
	}
	jvm := func(deps, constraints string) string {
		return `{"name": "jvmApiElements", "attributes": {"org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "jvm"},
			"dependencies": [` + deps + `], "dependencyConstraints": [` + constraints + `], "files": []}`
	}
	library("lib", "1", jvm(
		`{"group": "g", "module": "dep", "version": {"requires": "1"}},
		 {"group": "g", "module": "other", "version": {"requires": "1"}},
		 {"group": "g", "module": "bom", "version": {"requires": "1"}, "attributes": {"org.gradle.category": "platform"}}`,
		`{"group": "g", "module": "other", "version": {"requires": "3"}}, {"group": "g", "module": "absent", "version": {"requires": "1"}}`))
	library("bom", "1", `{"name": "apiElements", "attributes": {"org.gradle.category": "platform"},
		"dependencyConstraints": [{"group": "g", "module": "dep", "version": {"requires": "2"}}, {"group": "g", "module": "unused", "version": {"requires": "9"}}]}`)
	for _, v := range []string{"1", "2"} {
		library("dep", v, jvm("", ""))
	}
	for _, v := range []string{"1", "3"} {
		library("other", v, jvm("", ""))
	}
	r := NewResolver(repository(t, files), nil, nil)

	got, _, err := r.Resolve(platforms["jvm"], []Coordinate{{"g", "lib", "1"}})
	if err != nil {
		t.Fatal(err)
	}
	versions := map[string]string{}
	for m, res := range got {
		versions[m] = res.Version
	}
	want := map[string]string{"g:lib": "1", "g:dep": "2", "g:other": "3"}
	if len(versions) != len(want) {
		t.Errorf("resolved %v, want %v", versions, want)
	}
	for m, v := range want {
		if versions[m] != v {
			t.Errorf("%s = %q, want %s (all: %v)", m, versions[m], v, versions)
		}
	}
}

func TestPomBomConstrainsItsManagedVersions(t *testing.T) {
	files := map[string][]byte{}
	path, body := pom("g", "bom", "1", `<packaging>pom</packaging>`+managed(
		`<dependency><groupId>g</groupId><artifactId>dep</artifactId><version>2</version></dependency>`))
	files[path] = body
	path, body = pom("g", "lib", "1", "")
	files[path] = body
	files["/g/lib/1/lib-1.module"] = []byte(`{"variants": [{"name": "jvmApiElements",
		"attributes": {"org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "jvm"},
		"dependencies": [{"group": "g", "module": "dep", "version": {"requires": "1"}},
			{"group": "g", "module": "bom", "version": {"requires": "1"}, "attributes": {"org.gradle.category": "platform"}}]}]}`)
	for _, v := range []string{"1", "2"} {
		path, body = pom("g", "dep", v, "")
		files[path] = body
		files["/g/dep/"+v+"/dep-"+v+".jar"] = []byte("jar")
	}
	r := NewResolver(repository(t, files), nil, nil)

	got, _, err := r.Resolve(platforms["jvm"], []Coordinate{{"g", "lib", "1"}})
	if err != nil {
		t.Fatal(err)
	}
	if v := got["g:dep"].Version; v != "2" {
		t.Errorf("g:dep = %q, want 2", v)
	}
}

func TestPickVariantAcceptsOneNamingNoUsage(t *testing.T) {
	// kotlin-stdlib-common's only variant: no files, a dependency on kotlin-stdlib.
	common := []Variant{{Name: "stdlibCommonElements", Attributes: attrs{"org.gradle.category": "library"}}}
	if got := pick(platforms["jvm"], "java-runtime", common); got == nil {
		t.Error("rejected a variant that names no usage")
	}
	withJvm := append([]Variant{{Name: "jvmRuntimeElements", Attributes: attrs{"org.gradle.usage": "java-runtime", "org.jetbrains.kotlin.platform.type": "jvm"}}}, common...)
	if got := pick(platforms["jvm"], "java-runtime", withJvm); got == nil || got.Name != "jvmRuntimeElements" {
		t.Errorf("picked %v over the variant naming the usage", got)
	}
}

func TestPomVersion(t *testing.T) {
	for in, want := range map[string]string{"1.2": "1.2", "[0.150.2]": "0.150.2"} {
		if got, err := pomVersion(in); err != nil || got != want {
			t.Errorf("pomVersion(%q) = %q, %v; want %q", in, got, err, want)
		}
	}
	for _, in := range []string{"[1,2)", "(,1]", "[1.0,]"} {
		if _, err := pomVersion(in); err == nil {
			t.Errorf("pomVersion(%q) wants an error", in)
		}
	}
}
