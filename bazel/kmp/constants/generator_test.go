package main

import (
	"fmt"
	"strconv"
	"strings"
	"testing"
	"unicode/utf16"
)

// The cases of build-logic's GenerateKotlinConstantsTaskTest, which this generator must agree with.

func literal(s string) string { return kotlinStringLiteral(utf16.Encode([]rune(s))) }

func TestShortValuesRenderAsConstValsWithEscapes(t *testing.T) {
	source := render("a.b", "Gen", ":m:gen", map[string]string{"V": "x\t\"$\\\né"}, nil)

	for _, want := range []string{`const val V: String = "x\t\"$\\\n\u00e9"`, "package a.b", "internal object Gen {"} {
		if !strings.Contains(source, want) {
			t.Errorf("no %q in\n%s", want, source)
		}
	}
	if strings.Contains(source, "decode") {
		t.Errorf("decode in\n%s", source)
	}
}

func TestDollarIsEscapedOnlyWhereItWouldStartATemplate(t *testing.T) {
	if got, want := literal("a$b ${c} $1 $"), `"a\$b \${c} $1 $"`; got != want {
		t.Errorf("got %s, want %s", got, want)
	}
}

func TestLongValuesAreSplitIntoLiteralsThatJoinBackToTheValue(t *testing.T) {
	value := strings.Repeat("a", maxLiteralChars-1) + "😀" + strings.Repeat("b", maxLiteralChars)
	units := utf16.Encode([]rune(value))

	chunks := chunkLiteral(units)

	var joined []uint16
	for _, c := range chunks {
		if len(c) > maxLiteralChars {
			t.Errorf("chunk of %d", len(c))
		}
		joined = append(joined, c...)
	}
	if string(utf16.Decode(joined)) != value {
		t.Error("chunks don't join back to the value")
	}
	if isHighSurrogate(chunks[0][len(chunks[0])-1]) {
		t.Error("a surrogate pair was split")
	}
	if source := render("a", "Gen", ":gen", map[string]string{"BIG": value}, nil); !strings.Contains(source, "val BIG: String =\n        listOf(") {
		t.Errorf("not chunked:\n%.200s", source)
	}
}

// xorObfuscate is build-logic's, which google_oauth.bzl ports to Starlark.
func xorObfuscate(value string) string {
	var b strings.Builder
	for i, c := range utf16.Encode([]rune(value)) {
		fmt.Fprintf(&b, "%04x", int(c)^((xorBase+i)&0xFFFF))
	}
	return b.String()
}

func TestObfuscatedValuesDecodeBackAndNeverContainThePlaintext(t *testing.T) {
	secret := "GOCSPX-secret"
	hex := xorObfuscate(secret)

	source := render("a", "Gen", ":gen", nil, map[string]string{"SECRET": hex})

	if strings.Contains(source, "GOCSPX") {
		t.Errorf("plaintext in\n%s", source)
	}
	if !strings.Contains(source, "val SECRET: String = decode(intArrayOf(") {
		t.Errorf("no decode in\n%s", source)
	}
	// Mirrors the generated decode().
	var decoded []rune
	for i := 0; i < len(hex); i += 4 {
		code, _ := strconv.ParseInt(hex[i:i+4], 16, 32)
		decoded = append(decoded, rune(int(code)^((xorBase+i/4)&0xFFFF)))
	}
	if string(decoded) != secret {
		t.Errorf("decoded %q", string(decoded))
	}
}
