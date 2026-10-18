package main

import "testing"

func TestLaterEntryWins(t *testing.T) {
	got, err := entries([]string{"a.db=common/a.db", "b.csv=common/b.csv", "a.db=jvm/a.db"})
	if err != nil {
		t.Fatal(err)
	}
	if got["a.db"] != "jvm/a.db" || got["b.csv"] != "common/b.csv" || len(got) != 2 {
		t.Errorf("entries = %v", got)
	}
	if _, err := entries([]string{"no-separator"}); err == nil {
		t.Error("want an error for an argument without =")
	}
}
