The module checks' directives (#1014): `kmp_visibility` sets the library's visibility, `kmp_forbid_deps` reaches
the library and its tests, and `kmp_forbid_reference` / `kmp_allow_reference` set the reference check's regex,
reason and exempt files. None is inherited: `lib/open` gets the defaults, and its tests reach `lib/write` through
`:write_for_tests`: the rule is about main sources only.
