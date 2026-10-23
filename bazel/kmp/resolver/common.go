package main

import (
	"archive/zip"
	"bytes"
	"encoding/json"
	"fmt"
	"sort"
	"strings"
)

// The `metadata` platform is what a `commonMain` metadata compile sees (#1010): each library's common metadata
// variant, a jar of one klib directory per source set (`commonMain/`, `concurrentMain/`, …), and of those only
// the source sets every one of our targets' variants has, as Gradle's granular metadata picks them, with only
// the dependencies those source sets declare (the variant lists every source set's: Ktor's Darwin engine too).
const metadataPlatform = "metadata"

// metadataWeb is the same for modules that also compile for the browser: the source sets JVM, Android, native and
// the web platforms all share (Platform.SeesWeb).
const metadataWeb = "metadataWeb"

const structureMetadata = "META-INF/kotlin-project-structure-metadata.json"

type projectStructure struct {
	ProjectStructure struct {
		Variants []struct {
			Name      string
			SourceSet []string
		}
		SourceSets []struct {
			Name             string
			ModuleDependency []string
		}
	}
}

func (r *Resolver) loadMetadata(p Platform, c Coordinate, dir string, module *GradleModule) (*node, error) {
	var variant *Variant
	for i, v := range module.Variants {
		a := attrs(v.Attributes)
		usage, _ := a.str("org.gradle.usage")
		platform, _ := a.str("org.jetbrains.kotlin.platform.type")
		if usage == "kotlin-metadata" && platform == "common" {
			variant = &module.Variants[i]
			break
		}
	}
	if variant == nil {
		return nil, fmt.Errorf("%s: %w", c, errNoVariant)
	}
	if at := variant.AvailableAt; at != nil {
		return r.node(p, Coordinate{at.Group, at.Module, at.Version})
	}
	if len(variant.Files) != 1 {
		return nil, fmt.Errorf("%s: its common metadata variant has %d files, want 1", c, len(variant.Files))
	}
	f := variant.Files[0]
	file := File{Name: f.Name, URL: dir + "/" + f.URL, Sha256: f.Sha256}
	sourceSets, needs, err := r.visibleSourceSets(p, c, module, file)
	if err != nil {
		return nil, err
	}
	var deps []Dependency
	for _, d := range variant.Dependencies {
		if needs[d.Group+":"+d.Module] {
			deps = append(deps, d)
		}
	}
	return &node{
		files:       []File{file},
		api:         r.dependencies(deps),
		sourceSets:  sourceSets,
		platforms:   r.platformDependencies(variant.Dependencies),
		constraints: r.constraints(variant.DependencyConstraints),
	}, nil
}

// visibleSourceSets are the library's source sets that every consumer target's variant includes, and the
// modules they depend on. The web platforms are consumers only of `metadataWeb` (Platform.SeesWeb).
func (r *Resolver) visibleSourceSets(p Platform, c Coordinate, module *GradleModule, file File) ([]string, map[string]bool, error) {
	body, err := r.fetch.get(file.URL)
	if err != nil {
		return nil, nil, fmt.Errorf("%s: %w", file.Name, err)
	}
	archive, err := zip.NewReader(bytes.NewReader(body), int64(len(body)))
	if err != nil {
		return nil, nil, fmt.Errorf("%s: %w", file.Name, err)
	}
	var structure projectStructure
	found := false
	// A source set with no declarations of its own is listed but not published.
	published := map[string]bool{}
	for _, entry := range archive.File {
		if set, ok := strings.CutSuffix(entry.Name, "/default/manifest"); ok && !strings.Contains(set, "/") {
			published[set] = true
		}
		if entry.Name != structureMetadata {
			continue
		}
		rc, err := entry.Open()
		if err != nil {
			return nil, nil, err
		}
		err = json.NewDecoder(rc).Decode(&structure)
		rc.Close()
		if err != nil {
			return nil, nil, fmt.Errorf("%s %s: %w", file.Name, structureMetadata, err)
		}
		found = true
	}
	if !found {
		return nil, nil, fmt.Errorf("%s: no %s (a pre-granular-metadata library)", file.Name, structureMetadata)
	}
	byVariant := map[string][]string{}
	for _, v := range structure.ProjectStructure.Variants {
		byVariant[v.Name] = v.SourceSet
	}
	var visible map[string]bool
	for _, consumer := range r.consumers {
		if consumer.Web != "" && !p.SeesWeb {
			continue
		}
		sets, ok := consumerSourceSets(consumer, module, byVariant)
		if !ok || len(sets) == 0 {
			// No module that uses the library targets a platform it doesn't publish (that compile couldn't resolve
			// it), so that platform doesn't narrow what they share: Compose's UI has no Linux or Windows variant.
			// Nor does a variant whose code is another module (JetBrains Compose's Android one is AndroidX's).
			continue
		}
		next := map[string]bool{}
		for _, s := range sets {
			if visible == nil || visible[s] {
				next[s] = true
			}
		}
		visible = next
	}
	if visible == nil {
		return nil, nil, fmt.Errorf("%s: no variant for any consumer platform in its project structure: %w", c, errNoVariant)
	}
	// Android resolves a library's Android variant when it has one (the JVM one otherwise, counted above).
	for _, name := range []string{"androidReleaseApiElements", "androidApiElements"} {
		if sets, ok := byVariant[name]; ok && len(sets) > 0 {
			next := map[string]bool{}
			for _, s := range sets {
				if visible[s] {
					next[s] = true
				}
			}
			visible = next
			break
		}
	}
	var out []string
	for s := range visible {
		if published[s] {
			out = append(out, s)
		}
	}
	// None (Ktor's engine defaults, only dependencies): the library is what it depends on.
	sort.Strings(out)
	needs := map[string]bool{}
	for _, set := range structure.ProjectStructure.SourceSets {
		if visible[set.Name] {
			for _, m := range set.ModuleDependency {
				needs[m] = true
			}
		}
	}
	return out, needs, nil
}

// consumerSourceSets are the source sets of the variant `p` resolves: the variant Gradle picks, by name in the
// project structure (without the root module's `-published`), or the stdlib's one merged native variant.
func consumerSourceSets(p Platform, module *GradleModule, byVariant map[string][]string) ([]string, bool) {
	usage := "java-api"
	if p.IsKlib() {
		usage = "kotlin-api"
	}
	names := []string{p.Name + "ApiElements"}
	if v := pick(p, usage, module.Variants); v != nil {
		names = append([]string{strings.TrimSuffix(v.Name, "-published")}, names...)
	}
	if p.Konan != "" {
		names = append(names, "nativeApiElements")
	}
	for _, name := range names {
		if sets, ok := byVariant[name]; ok {
			return sets, true
		}
	}
	return nil, false
}
