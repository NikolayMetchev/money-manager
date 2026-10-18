package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/xml"
	"errors"
	"fmt"
	"io"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Repository is a Maven repository, optionally restricted to the groups it serves (Gradle's
// `content { includeGroupByRegex }`): Google's for androidx/com.android/com.google, Central for the rest.
type Repository struct {
	URL    string
	Groups []*regexp.Regexp // nil: every group
}

func (r Repository) serves(group string) bool {
	if r.Groups == nil {
		return true
	}
	for _, g := range r.Groups {
		if g.MatchString(group) {
			return true
		}
	}
	return false
}

// Fetcher downloads repository files: each URL once per run, a few at a time, retrying when a repository
// rate-limits us (Maven Central answers bursts with 429).
type Fetcher struct {
	Repos  []Repository
	client http.Client
	slots  chan struct{}
	mu     sync.Mutex
	calls  map[string]*call
}

type call struct {
	done chan struct{}
	body []byte
	err  error
}

var errNotFound = errors.New("not found")

func NewFetcher(repos []Repository) *Fetcher {
	return &Fetcher{Repos: repos, slots: make(chan struct{}, 4), calls: map[string]*call{}}
}

func (f *Fetcher) get(url string) ([]byte, error) {
	f.mu.Lock()
	c, ok := f.calls[url]
	if !ok {
		c = &call{done: make(chan struct{})}
		f.calls[url] = c
	}
	f.mu.Unlock()
	if ok {
		<-c.done
		return c.body, c.err
	}
	c.body, c.err = f.download(url)
	close(c.done)
	return c.body, c.err
}

func (f *Fetcher) download(url string) ([]byte, error) {
	f.slots <- struct{}{}
	defer func() { <-f.slots }()
	delay := time.Second
	for attempt := 1; ; attempt++ {
		body, status, err := f.once(url)
		retry := err != nil || status == http.StatusTooManyRequests || status >= 500
		if !retry || attempt == 6 {
			switch {
			case err != nil:
				return nil, err
			case status == http.StatusNotFound:
				return nil, errNotFound
			case status != http.StatusOK:
				return nil, fmt.Errorf("GET %s: HTTP %d", url, status)
			}
			return body, nil
		}
		time.Sleep(delay)
		delay *= 2
	}
}

func (f *Fetcher) once(url string) ([]byte, int, error) {
	req, err := http.NewRequest(http.MethodGet, url, nil)
	if err != nil {
		return nil, 0, err
	}
	// Maven Central turns Go's default User-Agent away with 429.
	req.Header.Set("User-Agent", "money-manager-kmp-resolver/1")
	resp, err := f.client.Do(req)
	if err != nil {
		return nil, 0, err
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	return body, resp.StatusCode, err
}

// moduleDir is where a module version's files live, in the first repository that has its POM.
func (f *Fetcher) moduleDir(c Coordinate) (string, error) {
	for _, repo := range f.Repos {
		if !repo.serves(c.Group) {
			continue
		}
		dir := fmt.Sprintf("%s/%s/%s/%s", strings.TrimSuffix(repo.URL, "/"), strings.ReplaceAll(c.Group, ".", "/"), c.Artifact, c.Version)
		if _, err := f.get(fmt.Sprintf("%s/%s-%s.pom", dir, c.Artifact, c.Version)); err == nil {
			return dir, nil
		} else if !errors.Is(err, errNotFound) {
			return "", err
		}
	}
	return "", fmt.Errorf("%s: not found in any repository", c)
}

// sha256Of hashes a file the metadata doesn't give a checksum for (a POM-only library's jar).
func (f *Fetcher) sha256Of(url string) (string, error) {
	body, err := f.get(url)
	if err != nil {
		return "", fmt.Errorf("GET %s: %w", url, err)
	}
	sum := sha256.Sum256(body)
	return hex.EncodeToString(sum[:]), nil
}

// GradleModule is Gradle Module Metadata (the `.module` file), the part we read.
type GradleModule struct {
	Component struct {
		Group, Module, Version string
	}
	Variants []Variant
}

type Variant struct {
	Name        string
	Attributes  map[string]any
	AvailableAt *struct {
		URL                    string
		Group, Module, Version string
	} `json:"available-at"`
	Dependencies []Dependency
	// DependencyConstraints raise a module's version when something else brings it in; they never add it.
	DependencyConstraints []Dependency `json:"dependencyConstraints"`
	Files                 []struct {
		Name, URL, Sha256 string
	}
}

type Dependency struct {
	Group, Module string
	Version       struct {
		Requires, Strictly, Prefers string
	}
	Attributes map[string]any
}

func (d Dependency) version() string {
	for _, v := range []string{d.Version.Strictly, d.Version.Requires, d.Version.Prefers} {
		if v != "" {
			return v
		}
	}
	return ""
}

func (f *Fetcher) gradleModule(dir string, c Coordinate) (*GradleModule, error) {
	body, err := f.get(fmt.Sprintf("%s/%s-%s.module", dir, c.Artifact, c.Version))
	if err != nil {
		return nil, err
	}
	var m GradleModule
	if err := json.Unmarshal(body, &m); err != nil {
		return nil, fmt.Errorf("%s.module: %w", c, err)
	}
	return &m, nil
}

// Pom is the part of a POM we read, for libraries that publish no Gradle Module Metadata.
type Pom struct {
	Parent *struct {
		GroupID    string `xml:"groupId"`
		ArtifactID string `xml:"artifactId"`
		Version    string `xml:"version"`
	} `xml:"parent"`
	GroupID    string `xml:"groupId"`
	Version    string `xml:"version"`
	Packaging  string `xml:"packaging"`
	Properties struct {
		Entries []struct {
			XMLName xml.Name
			Value   string `xml:",chardata"`
		} `xml:",any"`
	} `xml:"properties"`
	DependencyManagement struct {
		Dependencies []PomDependency `xml:"dependencies>dependency"`
	} `xml:"dependencyManagement"`
	Dependencies []PomDependency `xml:"dependencies>dependency"`
}

type PomDependency struct {
	GroupID    string `xml:"groupId"`
	ArtifactID string `xml:"artifactId"`
	Version    string `xml:"version"`
	Scope      string `xml:"scope"`
	Type       string `xml:"type"`
	Optional   string `xml:"optional"`
}

// effectivePom is a POM with its parents' properties and managed versions folded in.
type effectivePom struct {
	pom        *Pom
	properties map[string]string
	managed    map[string]string // group:artifact → version
}

var property = regexp.MustCompile(`\$\{([^}]+)\}`)

func (e *effectivePom) expand(s string) string {
	for i := 0; i < 10 && strings.Contains(s, "${"); i++ {
		s = property.ReplaceAllStringFunc(s, func(ref string) string {
			if v, ok := e.properties[ref[2:len(ref)-1]]; ok {
				return v
			}
			return ref
		})
	}
	return s
}

func (f *Fetcher) effectivePom(c Coordinate) (*effectivePom, error) {
	dir, err := f.moduleDir(c)
	if err != nil {
		return nil, err
	}
	body, err := f.get(fmt.Sprintf("%s/%s-%s.pom", dir, c.Artifact, c.Version))
	if err != nil {
		return nil, err
	}
	var pom Pom
	decoder := xml.NewDecoder(bytes.NewReader(body))
	decoder.CharsetReader = latin1Reader
	if err := decoder.Decode(&pom); err != nil {
		return nil, fmt.Errorf("%s.pom: %w", c, err)
	}
	e := &effectivePom{pom: &pom, properties: map[string]string{}, managed: map[string]string{}}
	if pom.Parent != nil {
		parent, err := f.effectivePom(Coordinate{pom.Parent.GroupID, pom.Parent.ArtifactID, pom.Parent.Version})
		if err != nil {
			return nil, fmt.Errorf("%s parent: %w", c, err)
		}
		for k, v := range parent.properties {
			e.properties[k] = v
		}
		for k, v := range parent.managed {
			e.managed[k] = v
		}
	}
	e.properties["project.version"] = c.Version
	e.properties["project.groupId"] = c.Group
	e.properties["pom.version"] = c.Version
	for _, p := range pom.Properties.Entries {
		e.properties[p.XMLName.Local] = p.Value
	}
	var boms []Coordinate
	own := map[string]bool{}
	for _, d := range pom.DependencyManagement.Dependencies {
		group, artifact, version := e.expand(d.GroupID), e.expand(d.ArtifactID), e.expand(d.Version)
		if d.Scope == "import" && d.Type == "pom" {
			boms = append(boms, Coordinate{group, artifact, version})
			continue
		}
		e.managed[group+":"+artifact] = version
		own[group+":"+artifact] = true
	}
	// A BOM's managed versions are this POM's too, over its parent's, unless the POM itself (or an earlier
	// BOM) manages the module.
	for _, coordinate := range boms {
		bom, err := f.effectivePom(coordinate)
		if err != nil {
			return nil, fmt.Errorf("%s BOM: %w", c, err)
		}
		for k, v := range bom.managed {
			if !own[k] {
				e.managed[k] = v
				own[k] = true
			}
		}
	}
	return e, nil
}

// latin1Reader decodes the ISO-8859-1 some old POMs declare (hamcrest's), which encoding/xml won't.
func latin1Reader(charset string, input io.Reader) (io.Reader, error) {
	switch strings.ToLower(charset) {
	case "iso-8859-1", "latin1", "us-ascii":
	default:
		return nil, fmt.Errorf("unsupported POM encoding %q", charset)
	}
	raw, err := io.ReadAll(input)
	if err != nil {
		return nil, err
	}
	runes := make([]rune, len(raw))
	for i, b := range raw {
		runes[i] = rune(b)
	}
	return strings.NewReader(string(runes)), nil
}
