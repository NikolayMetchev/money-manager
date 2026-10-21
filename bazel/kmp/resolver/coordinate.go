package main

import (
	"fmt"
	"strings"
)

// Coordinate is group:artifact:version.
type Coordinate struct {
	Group, Artifact, Version string
}

func (c Coordinate) Module() string { return c.Group + ":" + c.Artifact }
func (c Coordinate) String() string { return c.Module() + ":" + c.Version }

// ParseCoordinate reads group:artifact:version, each part non-empty.
func ParseCoordinate(s string) (Coordinate, error) {
	parts := strings.Split(s, ":")
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" {
		return Coordinate{}, fmt.Errorf("want group:artifact:version, got %q", s)
	}
	return Coordinate{parts[0], parts[1], parts[2]}, nil
}
