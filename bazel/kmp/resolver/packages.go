package main

import (
	"archive/zip"
	"bytes"
	"fmt"
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
	set := map[string]bool{}
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
