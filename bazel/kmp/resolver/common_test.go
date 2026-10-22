package main

import (
	"archive/zip"
	"bytes"
	"reflect"
	"testing"
)

func TestCommonMetadataSeesSharedSourceSetsAndTheirDependencies(t *testing.T) {
	var jar bytes.Buffer
	w := zip.NewWriter(&jar)
	for name, body := range map[string]string{
		"commonMain/default/manifest":     "",
		"concurrentMain/default/manifest": "",
		"nativeMain/default/manifest":     "",
		// emptyMain is in both targets' variants but declares nothing, so the jar has no klib for it.
		"META-INF/kotlin-project-structure-metadata.json": `{"projectStructure": {
			"variants": [
				{"name": "jvmApiElements", "sourceSet": ["commonMain", "concurrentMain", "emptyMain"]},
				{"name": "linuxX64ApiElements", "sourceSet": ["commonMain", "concurrentMain", "emptyMain", "nativeMain"]}
			],
			"sourceSets": [
				{"name": "commonMain", "moduleDependency": ["g:dep", "org.jetbrains.kotlin:kotlin-stdlib"]},
				{"name": "concurrentMain", "moduleDependency": ["g:dep"]},
				{"name": "nativeMain", "moduleDependency": ["g:native-only"]}
			]}}`,
	} {
		f, _ := w.Create(name)
		f.Write([]byte(body))
	}
	w.Close()
	module := `{"variants": [
		{"name": "metadataApiElements",
		 "attributes": {"org.gradle.usage": "kotlin-metadata", "org.jetbrains.kotlin.platform.type": "common"},
		 "dependencies": [
			{"group": "g", "module": "dep", "version": {"requires": "1"}},
			{"group": "g", "module": "native-only", "version": {"requires": "1"}}],
		 "files": [{"name": "lib-metadata-1.jar", "url": "lib-1.jar", "sha256": "x"}]},
		{"name": "jvmApiElements-published",
		 "attributes": {"org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "jvm"}},
		{"name": "linuxX64ApiElements-published",
		 "attributes": {"org.gradle.usage": "kotlin-api", "org.jetbrains.kotlin.platform.type": "native",
			"org.jetbrains.kotlin.native.target": "linux_x64"}}]}`
	path, body := pom("g", "lib", "1", "")
	f := repository(t, map[string][]byte{
		path:                    body,
		"/g/lib/1/lib-1.module": []byte(module),
		"/g/lib/1/lib-1.jar":    jar.Bytes(),
	})
	r := NewResolver(f, nil, nil)
	r.consumers = []Platform{platforms["jvm"], platforms["linuxX64"]}

	n, err := r.node(platforms[metadataPlatform], Coordinate{"g", "lib", "1"})
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"commonMain", "concurrentMain"}; !reflect.DeepEqual(n.sourceSets, want) {
		t.Errorf("source sets = %v, want %v", n.sourceSets, want)
	}
	if want := []Coordinate{{"g", "dep", "1"}}; !reflect.DeepEqual(n.api, want) {
		t.Errorf("deps = %v, want %v", n.api, want)
	}
}

// The web platforms only narrow what `metadataWeb` sees: a module without web targets keeps concurrentMain.
func TestOnlyWebMetadataExcludesWhatTheBrowserLacks(t *testing.T) {
	var jar bytes.Buffer
	w := zip.NewWriter(&jar)
	for name, body := range map[string]string{
		"commonMain/default/manifest":     "",
		"concurrentMain/default/manifest": "",
		"META-INF/kotlin-project-structure-metadata.json": `{"projectStructure": {
			"variants": [
				{"name": "jvmApiElements", "sourceSet": ["commonMain", "concurrentMain"]},
				{"name": "wasmJsApiElements", "sourceSet": ["commonMain", "webMain"]}
			],
			"sourceSets": [{"name": "commonMain"}, {"name": "concurrentMain"}]}}`,
	} {
		f, _ := w.Create(name)
		f.Write([]byte(body))
	}
	w.Close()
	module := `{"variants": [
		{"name": "metadataApiElements",
		 "attributes": {"org.gradle.usage": "kotlin-metadata", "org.jetbrains.kotlin.platform.type": "common"},
		 "files": [{"name": "lib-metadata-1.jar", "url": "lib-1.jar", "sha256": "x"}]},
		{"name": "jvmApiElements-published",
		 "attributes": {"org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "jvm"}},
		{"name": "wasmJsApiElements-published",
		 "attributes": {"org.gradle.usage": "kotlin-api", "org.jetbrains.kotlin.platform.type": "wasm",
			"org.jetbrains.kotlin.wasm.target": "js"}}]}`
	path, body := pom("g", "lib", "1", "")
	f := repository(t, map[string][]byte{
		path:                    body,
		"/g/lib/1/lib-1.module": []byte(module),
		"/g/lib/1/lib-1.jar":    jar.Bytes(),
	})
	r := NewResolver(f, nil, nil)
	r.consumers = []Platform{platforms["jvm"], platforms["wasmJs"]}

	for platform, want := range map[string][]string{
		metadataPlatform: {"commonMain", "concurrentMain"},
		metadataWeb:      {"commonMain"},
	} {
		n, err := r.node(platforms[platform], Coordinate{"g", "lib", "1"})
		if err != nil {
			t.Fatal(err)
		}
		if !reflect.DeepEqual(n.sourceSets, want) {
			t.Errorf("%s: source sets = %v, want %v", platform, n.sourceSets, want)
		}
	}
}

// A platform the library doesn't publish (Compose on Linux), or publishes as another module (its Android variant is
// AndroidX's), leaves the shared source sets to the platforms it does.
func TestAPlatformWithoutAVariantDoesNotNarrowCommonMetadata(t *testing.T) {
	var jar bytes.Buffer
	w := zip.NewWriter(&jar)
	for name, body := range map[string]string{
		"commonMain/default/manifest": "",
		"skikoMain/default/manifest":  "",
		"META-INF/kotlin-project-structure-metadata.json": `{"projectStructure": {
			"variants": [
				{"name": "androidApiElements", "sourceSet": []},
				{"name": "desktopApiElements", "sourceSet": ["commonMain", "skikoMain"]},
				{"name": "wasmJsApiElements", "sourceSet": ["commonMain", "skikoMain", "webMain"]}
			],
			"sourceSets": [{"name": "commonMain"}, {"name": "skikoMain"}]}}`,
	} {
		f, _ := w.Create(name)
		f.Write([]byte(body))
	}
	w.Close()
	module := `{"variants": [
		{"name": "metadataApiElements",
		 "attributes": {"org.gradle.usage": "kotlin-metadata", "org.jetbrains.kotlin.platform.type": "common"},
		 "files": [{"name": "lib-metadata-1.jar", "url": "lib-1.jar", "sha256": "x"}]},
		{"name": "desktopApiElements-published",
		 "attributes": {"org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "jvm"}},
		{"name": "wasmJsApiElements-published",
		 "attributes": {"org.gradle.usage": "kotlin-api", "org.jetbrains.kotlin.platform.type": "wasm",
			"org.jetbrains.kotlin.wasm.target": "js"}}]}`
	path, body := pom("g", "lib", "1", "")
	f := repository(t, map[string][]byte{
		path:                    body,
		"/g/lib/1/lib-1.module": []byte(module),
		"/g/lib/1/lib-1.jar":    jar.Bytes(),
	})
	r := NewResolver(f, nil, nil)
	r.consumers = []Platform{platforms["android"], platforms["jvm"], platforms["linuxX64"], platforms["wasmJs"]}

	n, err := r.node(platforms[metadataWeb], Coordinate{"g", "lib", "1"})
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"commonMain", "skikoMain"}; !reflect.DeepEqual(n.sourceSets, want) {
		t.Errorf("source sets = %v, want %v", n.sourceSets, want)
	}
}

func TestExcludingWhereAVariantPointsLeavesItEmpty(t *testing.T) {
	path, body := pom("g", "lib", "1", "")
	module := `{"variants": [
		{"name": "jvmApiElements-published",
		 "attributes": {"org.gradle.usage": "java-api", "org.jetbrains.kotlin.platform.type": "jvm"},
		 "available-at": {"url": "../../lib-desktop/1/lib-desktop-1.module", "group": "g", "module": "lib-desktop", "version": "1"}}]}`
	f := repository(t, map[string][]byte{path: body, "/g/lib/1/lib-1.module": []byte(module)})
	r := NewResolver(f, nil, []string{"g:lib-desktop"})

	n, err := r.node(platforms["jvm"], Coordinate{"g", "lib", "1"})
	if err != nil {
		t.Fatal(err)
	}
	if len(n.files) != 0 || len(n.api) != 0 {
		t.Errorf("node = %+v, want no files or deps", n)
	}
}
