One package, one module (#1014): `a` and `b` both declare `shared` in their main sources, so the run fails and
names both. Their tests may share a package (`b`'s test declares `a`'s package), as Gradle's check allows.
