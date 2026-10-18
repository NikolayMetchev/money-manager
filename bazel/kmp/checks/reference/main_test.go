package main

import (
	"os"
	"path/filepath"
	"reflect"
	"regexp"
	"testing"
)

func TestScanSkipsCommentsAndReportsLines(t *testing.T) {
	path := filepath.Join(t.TempDir(), "A.kt")
	src := "package a\n" +
		"// FooWriteRepository in a line comment\n" +
		"/** KDoc naming FooWriteRepository */\n" +
		" * FooWriteRepository in a block comment\n" +
		"class A(private val repo: FooWriteRepository)\n" +
		"val notOne = FooWriteRepositoryX\n"
	if err := os.WriteFile(path, []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}
	found, err := scan(path, regexp.MustCompile(`\b[A-Za-z]+WriteRepository\b`))
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"5  class A(private val repo: FooWriteRepository)"}; !reflect.DeepEqual(found, want) {
		t.Errorf("found %q, want %q", found, want)
	}
}
