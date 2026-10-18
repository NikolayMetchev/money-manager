package main

import (
	"bufio"
	"fmt"
	"os"
	"regexp"
	"strings"
)

// Catalog is the part of libs.versions.toml the resolver reads: [versions] and [libraries].
type Catalog struct {
	Versions  map[string]string
	Libraries map[string]Coordinate // alias (kotlinx-coroutines-core) → coordinate
}

// Coordinate is group:artifact:version.
type Coordinate struct {
	Group, Artifact, Version string
}

func (c Coordinate) Module() string { return c.Group + ":" + c.Artifact }
func (c Coordinate) String() string { return c.Module() + ":" + c.Version }

var (
	tableLine   = regexp.MustCompile(`^\[([\w.-]+)\]$`)
	versionLine = regexp.MustCompile(`^([\w.-]+)\s*=\s*"([^"]*)"$`)
	libraryLine = regexp.MustCompile(`^([\w.-]+)\s*=\s*\{(.*)\}$`)
	inlineField = regexp.MustCompile(`([\w.]+)\s*=\s*"([^"]*)"`)
)

// ReadCatalog parses the catalog's subset of TOML: one `key = "value"` per line in [versions], and one
// inline table (`module = "g:a"` or `group`/`name`, plus `version.ref` or `version`) per line in [libraries].
// That's every form the catalog uses; anything else in those tables is an error rather than silently skipped.
func ReadCatalog(path string) (*Catalog, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	cat := &Catalog{Versions: map[string]string{}, Libraries: map[string]Coordinate{}}
	type rawLib struct{ alias, group, artifact, version, ref string }
	var libs []rawLib
	table := ""
	scanner := bufio.NewScanner(f)
	for n := 1; scanner.Scan(); n++ {
		line := stripComment(scanner.Text())
		if line == "" {
			continue
		}
		if m := tableLine.FindStringSubmatch(line); m != nil {
			table = m[1]
			continue
		}
		switch table {
		case "versions":
			m := versionLine.FindStringSubmatch(line)
			if m == nil {
				return nil, fmt.Errorf("%s:%d: unsupported [versions] entry: %s", path, n, line)
			}
			cat.Versions[m[1]] = m[2]
		case "libraries":
			m := libraryLine.FindStringSubmatch(line)
			if m == nil {
				return nil, fmt.Errorf("%s:%d: unsupported [libraries] entry: %s", path, n, line)
			}
			lib := rawLib{alias: m[1]}
			for _, field := range inlineField.FindAllStringSubmatch(m[2], -1) {
				switch field[1] {
				case "module":
					ga := strings.SplitN(field[2], ":", 2)
					if len(ga) != 2 {
						return nil, fmt.Errorf("%s:%d: bad module %q", path, n, field[2])
					}
					lib.group, lib.artifact = ga[0], ga[1]
				case "group":
					lib.group = field[2]
				case "name":
					lib.artifact = field[2]
				case "version.ref":
					lib.ref = field[2]
				case "version":
					lib.version = field[2]
				}
			}
			libs = append(libs, lib)
		}
	}
	if err := scanner.Err(); err != nil {
		return nil, err
	}
	for _, lib := range libs {
		version := lib.version
		if lib.ref != "" {
			v, ok := cat.Versions[lib.ref]
			if !ok {
				return nil, fmt.Errorf("%s: library %s refers to unknown version %q", path, lib.alias, lib.ref)
			}
			version = v
		}
		cat.Libraries[lib.alias] = Coordinate{lib.group, lib.artifact, version}
	}
	return cat, nil
}

// stripComment drops a `#` comment that isn't inside a string, and surrounding space.
func stripComment(line string) string {
	quoted := false
	for i, c := range line {
		switch {
		case c == '"':
			quoted = !quoted
		case c == '#' && !quoted:
			return strings.TrimSpace(line[:i])
		}
	}
	return strings.TrimSpace(line)
}
