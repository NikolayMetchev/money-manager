package main

import (
	"archive/zip"
	"bytes"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
)

// repository serves files by path, as a Maven repository does.
func repository(t *testing.T, files map[string][]byte) *Fetcher {
	t.Helper()
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, ok := files[r.URL.Path]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Write(body)
	}))
	t.Cleanup(server.Close)
	return NewFetcher([]Repository{{URL: server.URL}})
}

func pom(group, artifact, version, body string) (string, []byte) {
	path := "/" + group + "/" + artifact + "/" + version + "/" + artifact + "-" + version + ".pom"
	return path, []byte(`<project><groupId>` + group + `</groupId><artifactId>` + artifact + `</artifactId><version>` +
		version + `</version>` + body + `</project>`)
}

func managed(entries string) string {
	return `<dependencyManagement><dependencies>` + entries + `</dependencies></dependencyManagement>`
}

func TestImportedBomManagesVersions(t *testing.T) {
	files := map[string][]byte{}
	add := func(path string, body []byte) { files[path] = body }
	add(pom("g", "parent", "1", managed(
		`<dependency><groupId>g</groupId><artifactId>api</artifactId><version>0.1</version></dependency>`+
			`<dependency><groupId>g</groupId><artifactId>core</artifactId><version>0.1</version></dependency>`)))
	add(pom("g", "bom", "2", managed(
		`<dependency><groupId>g</groupId><artifactId>api</artifactId><version>2.0</version></dependency>`+
			`<dependency><groupId>g</groupId><artifactId>own</artifactId><version>2.0</version></dependency>`)))
	add(pom("g", "lib", "3", `<parent><groupId>g</groupId><artifactId>parent</artifactId><version>1</version></parent>`+
		managed(`<dependency><groupId>g</groupId><artifactId>bom</artifactId><version>2</version>`+
			`<type>pom</type><scope>import</scope></dependency>`+
			`<dependency><groupId>g</groupId><artifactId>own</artifactId><version>3.0</version></dependency>`)))

	e, err := repository(t, files).effectivePom(Coordinate{"g", "lib", "3"})
	if err != nil {
		t.Fatal(err)
	}
	want := map[string]string{
		"g:api":  "2.0", // the BOM over the parent
		"g:core": "0.1", // only the parent manages it
		"g:own":  "3.0", // the POM's own entry over the BOM, whatever their order
	}
	if !reflect.DeepEqual(e.managed, want) {
		t.Errorf("managed = %v, want %v", e.managed, want)
	}
}

func TestCinteropEnclosingPackagesAreDropped(t *testing.T) {
	var klib bytes.Buffer
	w := zip.NewWriter(&klib)
	for name, size := range map[string]int{
		"default/linkdata/package_dev/0_dev.knm":                  13,
		"default/linkdata/package_dev.whyoleg/0_whyoleg.knm":      21,
		"default/linkdata/package_dev.whyoleg.ssl.cinterop/0.knm": 4096,
		"default/linkdata/package_lone/0_lone.knm":                13,
	} {
		f, _ := w.Create(name)
		f.Write(make([]byte, size))
	}
	w.Close()
	f := repository(t, map[string][]byte{"/a.klib": klib.Bytes()})

	got, err := f.packagesOf(File{Name: "a.klib", URL: f.Repos[0].URL + "/a.klib"})
	if err != nil {
		t.Fatal(err)
	}
	// An empty package that encloses nothing else is still the klib's own (a package with only typealiases).
	want := []string{"dev.whyoleg.ssl.cinterop", "lone"}
	if !reflect.DeepEqual(got, want) {
		t.Errorf("packages = %v, want %v", got, want)
	}
}
