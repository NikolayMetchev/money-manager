package main

import (
	"archive/zip"
	"bytes"
	"fmt"
	"io"
	"sort"
	"strings"
)

// packagesOf lists the Kotlin/Java packages a jar or klib declares, so Gazelle (#1004) can map an import to
// the library that provides it: a jar's class directories, a klib's `default/linkdata/package_<name>` entries.
func (f *Fetcher) packagesOf(file File) ([]string, error) {
	body, err := f.get(file.URL)
	if err != nil {
		return nil, fmt.Errorf("%s: %w", file.Name, err)
	}
	archive, err := zip.NewReader(bytes.NewReader(body), int64(len(body)))
	if err != nil {
		return nil, fmt.Errorf("%s: %w", file.Name, err)
	}
	if strings.HasSuffix(file.Name, ".aar") {
		// An AAR's classes are its classes.jar (and any libs/*.jar), which is what an Android compile sees.
		return aarPackages(file.Name, archive)
	}
	set := map[string]bool{}
	// cinterop writes a near-empty fragment for every package enclosing its own (`dev`, `dev.whyoleg`, …); they
	// declare nothing, and listing them would map another library's package to this klib.
	klibBytes := map[string]uint64{}
	for _, entry := range archive.File {
		name := entry.Name
		switch {
		case strings.HasSuffix(name, ".class"):
			if strings.HasPrefix(name, "META-INF/") || !strings.Contains(name, "/") {
				continue
			}
			set[strings.ReplaceAll(name[:strings.LastIndex(name, "/")], "/", ".")] = true
		case strings.Contains(name, "/linkdata/package_"):
			pkg := name[strings.Index(name, "/linkdata/package_")+len("/linkdata/package_"):]
			pkg = strings.TrimSuffix(pkg[:strings.IndexByte(pkg+"/", '/')], "/")
			if pkg != "" {
				set[pkg] = true
				klibBytes[pkg] += entry.UncompressedSize64
			}
		}
	}
	for pkg, size := range klibBytes {
		if size < emptyFragment && enclosesAnother(pkg, set) {
			delete(set, pkg)
		}
	}
	var out []string
	for p := range set {
		out = append(out, p)
	}
	sort.Strings(out)
	return out, nil
}

func aarPackages(name string, aar *zip.Reader) ([]string, error) {
	set := map[string]bool{}
	for _, entry := range aar.File {
		if entry.Name != "classes.jar" && !(strings.HasPrefix(entry.Name, "libs/") && strings.HasSuffix(entry.Name, ".jar")) {
			continue
		}
		rc, err := entry.Open()
		if err != nil {
			return nil, fmt.Errorf("%s %s: %w", name, entry.Name, err)
		}
		body, err := io.ReadAll(rc)
		rc.Close()
		if err != nil {
			return nil, fmt.Errorf("%s %s: %w", name, entry.Name, err)
		}
		jar, err := zip.NewReader(bytes.NewReader(body), int64(len(body)))
		if err != nil {
			return nil, fmt.Errorf("%s %s: %w", name, entry.Name, err)
		}
		for _, f := range jar.File {
			if strings.HasSuffix(f.Name, ".class") && !strings.HasPrefix(f.Name, "META-INF/") && strings.Contains(f.Name, "/") {
				set[strings.ReplaceAll(f.Name[:strings.LastIndex(f.Name, "/")], "/", ".")] = true
			}
		}
	}
	var out []string
	for p := range set {
		out = append(out, p)
	}
	sort.Strings(out)
	return out, nil
}

// emptyFragment bounds a package fragment with no declarations: its header and name, nothing else.
const emptyFragment = 64

func enclosesAnother(pkg string, set map[string]bool) bool {
	for other := range set {
		if strings.HasPrefix(other, pkg+".") {
			return true
		}
	}
	return false
}
