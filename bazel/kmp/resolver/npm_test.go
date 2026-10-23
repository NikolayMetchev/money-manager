package main

import (
	"archive/zip"
	"bytes"
	"net/http"
	"net/http/httptest"
	"reflect"
	"strings"
	"testing"
)

func npmServer(t *testing.T, files map[string]string) *httptest.Server {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, ok := files[r.URL.Path]
		if !ok {
			http.NotFound(w, r)
			return
		}
		w.Write([]byte(body))
	}))
	t.Cleanup(server.Close)
	return server
}

func npmMeta(deps, peers, peerMeta string) string {
	return `{"dependencies":` + deps + `,"peerDependencies":` + peers + `,"peerDependenciesMeta":` + peerMeta +
		`,"dist":{"tarball":"https://registry.example/t.tgz","integrity":"sha512-abc"}}`
}

func TestNpmDepsOfReadsTheKlibPackageJson(t *testing.T) {
	var klib bytes.Buffer
	w := zip.NewWriter(&klib)
	f, _ := w.Create("package.json")
	f.Write([]byte(`{"name":"x","dependencies":{"@js-joda/core":"3.2.0"}}`))
	w.Close()
	server := npmServer(t, map[string]string{"/x.klib": klib.String()})

	deps, err := NewFetcher(nil).npmDepsOf(File{Name: "x.klib", URL: server.URL + "/x.klib"})
	if err != nil {
		t.Fatal(err)
	}
	if want := map[string]string{"@js-joda/core": "3.2.0"}; !reflect.DeepEqual(deps, want) {
		t.Errorf("got %v, want %v", deps, want)
	}
}

func TestNpmClosureForcesVersionsAndInstallsRequiredPeers(t *testing.T) {
	server := npmServer(t, map[string]string{
		"/@js-joda/timezone/3.1.0": npmMeta(`{}`, `{"@js-joda/core":">=5.7.0","optional-peer":"^1.0.0"}`, `{"optional-peer":{"optional":true}}`),
		"/@js-joda/core/6.3.0":     npmMeta(`{}`, `{}`, `{}`),
	})
	r := npmResolver{fetch: NewFetcher(nil), registry: server.URL, forces: map[string]string{"@js-joda/core": "6.3.0"}, into: map[string]NpmPackage{}}

	if err := r.closure(r.pinned(map[string]string{"@js-joda/timezone": "3.1.0"})); err != nil {
		t.Fatal(err)
	}
	var keys []string
	for k := range r.into {
		keys = append(keys, k)
	}
	if len(keys) != 2 || r.into["@js-joda/core@6.3.0"].Name != "@js-joda/core" {
		t.Fatalf("got %v, want timezone and the forced core", keys)
	}
	if got := r.into["@js-joda/timezone@3.1.0"].Deps; !reflect.DeepEqual(got, map[string]string{"@js-joda/core": "6.3.0"}) {
		t.Errorf("timezone deps: got %v", got)
	}
}

func TestNpmClosureRejectsARange(t *testing.T) {
	server := npmServer(t, map[string]string{"/a/1.0.0": npmMeta(`{"b":"^2.0.0"}`, `{}`, `{}`)})
	r := npmResolver{fetch: NewFetcher(nil), registry: server.URL, forces: map[string]string{}, into: map[string]NpmPackage{}}

	err := r.closure(map[string]string{"a": "1.0.0"})
	if err == nil || !strings.Contains(err.Error(), "b@^2.0.0") {
		t.Fatalf("got %v, want an error naming b@^2.0.0", err)
	}
}

func TestNpmClosureAcceptsAPreReleaseVersion(t *testing.T) {
	server := npmServer(t, map[string]string{"/a/3.54.1-build1": npmMeta(`{}`, `{}`, `{}`)})
	r := npmResolver{fetch: NewFetcher(nil), registry: server.URL, forces: map[string]string{}, into: map[string]NpmPackage{}}

	if err := r.closure(map[string]string{"a": "3.54.1-build1"}); err != nil {
		t.Fatal(err)
	}
	if _, ok := r.into["a@3.54.1-build1"]; !ok {
		t.Errorf("a@3.54.1-build1 not pinned: %v", r.into)
	}
}
