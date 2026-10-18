// Command resources writes a module's Java resources into a jar (kmp_resource_jar, //bazel/kmp:resources.bzl).
//
// Each argument is `entry=file`. A later argument for the same entry wins, as a KMP compilation's leaf source
// set's resources win over commonMain's. Entries are written sorted, with a fixed time, so the jar is the same
// on every machine.
package main

import (
	"archive/zip"
	"flag"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
	"time"
)

func main() {
	out := flag.String("out", "", "the jar to write")
	flag.Parse()
	if err := write(*out, flag.Args()); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func entries(args []string) (map[string]string, error) {
	files := map[string]string{}
	for _, arg := range args {
		entry, file, ok := strings.Cut(arg, "=")
		if !ok || entry == "" || file == "" {
			return nil, fmt.Errorf("want entry=file, got %q", arg)
		}
		files[entry] = file
	}
	return files, nil
}

func write(out string, args []string) error {
	files, err := entries(args)
	if err != nil {
		return err
	}
	names := make([]string, 0, len(files))
	for name := range files {
		names = append(names, name)
	}
	sort.Strings(names)
	f, err := os.Create(out)
	if err != nil {
		return err
	}
	defer f.Close()
	w := zip.NewWriter(f)
	// The fixed time Bazel's own jars carry.
	stamp := time.Date(2010, 1, 1, 0, 0, 0, 0, time.UTC)
	for _, name := range names {
		dst, err := w.CreateHeader(&zip.FileHeader{Name: name, Method: zip.Deflate, Modified: stamp})
		if err != nil {
			return err
		}
		src, err := os.Open(files[name])
		if err != nil {
			return err
		}
		_, err = io.Copy(dst, src)
		src.Close()
		if err != nil {
			return err
		}
	}
	if err := w.Close(); err != nil {
		return err
	}
	return f.Close()
}
