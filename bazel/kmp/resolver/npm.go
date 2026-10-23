package main

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"regexp"
	"strings"
)

// npmRegistry is where a browser klib's npm packages come from, as KGP's Yarn setup fetches them.
const npmRegistry = "https://registry.npmjs.org"

// NpmPackage is one npm package a browser klib needs at runtime (kotlinx-datetime's `@js-joda/core`), pinned by the
// registry's tarball and its SRI integrity.
type NpmPackage struct {
	Name      string            `json:"name"`
	Version   string            `json:"version"`
	URL       string            `json:"url"`
	Integrity string            `json:"integrity"`
	Deps      map[string]string `json:"deps,omitempty"`
}

func npmKey(name, version string) string { return name + "@" + version }

// npmDepsOf reads the npm dependencies a browser klib declares in its `package.json`, which KGP installs with Yarn.
func (f *Fetcher) npmDepsOf(file File) (map[string]string, error) {
	body, err := f.get(file.URL)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", file.Name, err)
	}
	archive, err := zip.NewReader(bytes.NewReader(body), int64(len(body)))
	if err != nil {
		return nil, fmt.Errorf("%s: %w", file.Name, err)
	}
	for _, entry := range archive.File {
		if entry.Name != "package.json" {
			continue
		}
		rc, err := entry.Open()
		if err != nil {
			return nil, fmt.Errorf("%s package.json: %w", file.Name, err)
		}
		data, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			return nil, fmt.Errorf("%s package.json: %w", file.Name, err)
		}
		var pkg struct {
			Dependencies map[string]string `json:"dependencies"`
		}
		if err := json.Unmarshal(data, &pkg); err != nil {
			return nil, fmt.Errorf("%s package.json: %w", file.Name, err)
		}
		return pkg.Dependencies, nil
	}
	return nil, nil
}

// An exact semver version, a pre-release tag included (@sqlite.org/sqlite-wasm publishes `3.54.1-build1`).
var exactVersion = regexp.MustCompile(`^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$`)

// npmResolver pins npm packages and everything they need into `into`, by name@version.
type npmResolver struct {
	fetch    *Fetcher
	registry string
	forces   map[string]string
	into     map[string]NpmPackage
}

// pinned is `deps` with the forced versions.
func (r npmResolver) pinned(deps map[string]string) map[string]string {
	out := map[string]string{}
	for name, version := range deps {
		if forced, ok := r.forces[name]; ok {
			version = forced
		}
		out[name] = version
	}
	return out
}

// closure pins `deps` (already pinned) and every package they depend on, required peers included, as npm 7 installs
// them. A
// klib names exact versions, and a package asking for a range needs a force: npm's version resolution is left out
// rather than guessed at.
func (r npmResolver) closure(deps map[string]string) error {
	for name, version := range deps {
		if !exactVersion.MatchString(version) {
			return fmt.Errorf("npm %s@%s: only exact versions are supported; force one with npm_forces", name, version)
		}
		key := npmKey(name, version)
		if _, ok := r.into[key]; ok {
			continue
		}
		body, err := r.fetch.get(fmt.Sprintf("%s/%s/%s", r.registry, name, version))
		if err != nil {
			return fmt.Errorf("npm %s: %w", key, err)
		}
		var meta struct {
			Dependencies     map[string]string `json:"dependencies"`
			PeerDependencies map[string]string `json:"peerDependencies"`
			PeerMeta         map[string]struct {
				Optional bool `json:"optional"`
			} `json:"peerDependenciesMeta"`
			Dist struct {
				Tarball   string `json:"tarball"`
				Integrity string `json:"integrity"`
			} `json:"dist"`
		}
		if err := json.Unmarshal(body, &meta); err != nil {
			return fmt.Errorf("npm %s: %w", key, err)
		}
		if !strings.HasPrefix(meta.Dist.Integrity, "sha512-") {
			return fmt.Errorf("npm %s: no sha512 integrity", key)
		}
		needs := map[string]string{}
		for n, v := range meta.PeerDependencies {
			if !meta.PeerMeta[n].Optional {
				needs[n] = v
			}
		}
		for n, v := range meta.Dependencies {
			needs[n] = v
		}
		needs = r.pinned(needs)
		pkg := NpmPackage{Name: name, Version: version, URL: meta.Dist.Tarball, Integrity: meta.Dist.Integrity}
		if len(needs) > 0 {
			pkg.Deps = needs
		}
		r.into[key] = pkg
		if err := r.closure(needs); err != nil {
			return err
		}
	}
	return nil
}
