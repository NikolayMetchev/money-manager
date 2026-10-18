// Command reference fails when a Kotlin source names what its module must not (kmp_reference_check, #1014).
// Comment lines (`//`, `/*`, `*`) may name it in prose.
package main

import (
	"bufio"
	"flag"
	"fmt"
	"os"
	"regexp"
	"strings"
)

func main() {
	pattern := flag.String("regex", "", "what sources must not match")
	reason := flag.String("reason", "", "why, for the message")
	module := flag.String("module", "", "the module, for the message")
	strip := flag.String("strip", "", "prefix to strip from paths in the message")
	out := flag.String("out", "", "stamp file written on success")
	flag.Parse()
	re, err := regexp.Compile(*pattern)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
	var offenders []string
	for _, path := range flag.Args() {
		found, err := scan(path, re)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			os.Exit(2)
		}
		for _, f := range found {
			offenders = append(offenders, strings.TrimPrefix(path, *strip)+":"+f)
		}
	}
	if len(offenders) > 0 {
		fmt.Fprintf(os.Stderr, "%s must not match %s: %s\nOffending references:\n", *module, *pattern, *reason)
		for _, o := range offenders {
			fmt.Fprintf(os.Stderr, "  %s\n", o)
		}
		os.Exit(1)
	}
	if err := os.WriteFile(*out, nil, 0o644); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(2)
	}
}

// scan returns `line  text` for each non-comment line of path that matches re.
func scan(path string, re *regexp.Regexp) ([]string, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var found []string
	sc := bufio.NewScanner(f)
	sc.Buffer(make([]byte, 1024*1024), 1024*1024)
	for n := 1; sc.Scan(); n++ {
		line := strings.TrimSpace(sc.Text())
		if strings.HasPrefix(line, "*") || strings.HasPrefix(line, "//") || strings.HasPrefix(line, "/*") {
			continue
		}
		if re.MatchString(line) {
			found = append(found, fmt.Sprintf("%d  %s", n, line))
		}
	}
	return found, sc.Err()
}
