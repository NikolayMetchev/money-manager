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
