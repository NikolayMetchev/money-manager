A module with `src/commonMain/sqldelight` gets a `sqldelight_library` named by its package directive, with
upstream databases from `kmp_sqldelight_dep`; its Kotlin imports of the generated package need no dependency.
A module with only `.sq` files is still a module.
