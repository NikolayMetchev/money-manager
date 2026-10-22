# How the build works

Money Manager builds with Bazel (bzlmod only: `MODULE.bazel` plus the committed `MODULE.bazel.lock`), run through
bazelisk at the version in `.bazelversion`. Bazel has no Kotlin Multiplatform rules of its own, so this directory
holds them. This page is for whoever changes the build: bumping Kotlin, adding a plugin, a platform or a check.
How to *use* the build is in `CLAUDE.md` (Build Commands).

Where the rules copy an upstream tool's behaviour, they copy the Kotlin Gradle Plugin (KGP), the Android Gradle
Plugin (AGP) or Compose Multiplatform's desktop packaging. Those stay the reference when something has to change:
read what the upstream plugin passes, then pass the same.

| Directory | What it is |
|---|---|
| `kmp/` | `kmp_library`, `kmp_test` and the rules around them (`defs.bzl`), the Kotlin toolchain |
| `kmp/plugins/` | Compiler plugins: serialization, Compose, Metro, Mappie, Mokkery |
| `kmp/native/` | The Kotlin/Native toolchain (konan), its pins and the repin tool |
| `kmp/resolver/`, `kmp/deps/` | `maven_kmp`, the Maven resolver (Go), its lockfiles and the dependency snapshot |
| `kmp/sqldelight/`, `kmp/constants/`, `kmp/sqlite/` | SQLDelight, generated Kotlin constants, the bundled SQLite |
| `kmp/checks/`, `kmp/lint/` | The module checks, unused deps, ktlint and detekt |
| `kmp/android/` | The Android SDK, library compiles, host tests, APKs, device tests and lint |
| `kmp/proguard/`, `kmp/jpackage/` | The shrunk desktop jars and the desktop installers |
| `kmp/dokka/`, `pages/` | KDoc and the GitHub Pages site |
| `gazelle/kmp/` | The Gazelle extension (Go) that writes every module's `BUILD.bazel` |
| `third_party/rules_kotlin/` | The patch that gives rules_kotlin KMP fragments |
| `platforms/`, `stamp/`, `renovate/` | Target platforms, `--stamp`'s git commit, the re-pin script |

## Bumping Kotlin

Kotlin has one version, and four things must agree on it:

1. `KOTLIN` in `MODULE.bazel` (the Kotlin libraries `maven_kmp` resolves), with the Compose compiler, Metro and
   Mappie releases built for it.
2. `bazel_dep(name = "rules_kotlin")` in `MODULE.bazel`: rules_kotlin bundles the compiler Bazel runs, so its
   version is the Kotlin release.
3. `bazel/third_party/rules_kotlin/fragments.patch`, rebased onto the new rules_kotlin when it no longer applies.
4. `bazel/kmp/native/pins.bzl`, from `bazel run @kotlin_native_repin//:repin`. It downloads every archive, because
   JetBrains' `.sha256` files all hold the same wrong checksum.

`bazel/renovate/repin.sh` does 4 and re-pins every lockfile. Renovate's `kotlin monorepo` group bumps 1 and 2 in one
PR, and `renovate-pins.yml` runs the script on it. 3 is by hand.

## Modules and fragments

- **`kmp_library` / `kmp_test`** (`kmp/defs.bzl`) compile a module the way KGP does: one K2 call per platform over
  every fragment (`commonMain ← jvmAndroidMain ← jvmMain`; `commonMain ← nativeMain ← linuxMain|appleMain|mingwMain`),
  with empty ones left out, so expect/actual and common code are checked per fragment. A module emits `:<name>_jvm`
  and one `:<name>_<target>` per `native_targets` entry (`_linuxX64`, …). Deps are written once, as module labels
  (`//utils/files:files`), and each platform gets that label's `_<platform>` target, Maven libraries included.
  `exports` are API deps: also on the module's own classpath, and on native every klib dep is transitive anyway.
  Klibs are uniquely named after the full path (`utils.bigdecimal:utils-bigdecimal`) and compiled `-g -Werror`.
- **Tests**: JVM tests run through `KmpJUnitRunner` (JUnit 4) as `:test_jvm`. Native tests link with konan's test
  runner and run through `KnTestLauncher`, which turns its TeamCity output into `test.xml`, as `:test_<target>`, only
  where the binary runs (on Linux x64, `_linuxX64`; CI cross-links `_linuxArm64` and runs it on an arm64 runner).
  Tests default to `medium`; a slower suite sets `# gazelle:kmp_test_size`, and one that reads files from the tree
  at run time `# gazelle:kmp_test_data` (paths relative to the workspace root, the runfiles' working directory).
  Test JVMs use `/tmp`. Every module's JVM runtime carries `kotlinx-coroutines-swing`.
- **The compile is rules_kotlin's `kt_jvm_library`, patched**: rules_kotlin has no fragment flags, so
  `third_party/rules_kotlin/fragments.patch` (via `single_version_override`) adds `fragment_srcs` / `fragment_refines`
  and an `x_expect_actual_classes` option. It is Starlark only. The toolchain is `//bazel/kmp:kotlin_toolchain`
  (`-Werror`, JVM 25, indy lambdas). ABI jars stay off: Metro and Compose metadata must reach downstream compiles.
  `.bazelrc` sets rules_kotlin's strict associates: a test's friend is only its module's jar (otherwise K2
  smart-casts the exports').
- **Module names**: every JVM and Android compile is named as KGP names it (`app.ui.core_core`), because Compose's
  lambda class names and `internal` members carry it. `app/main/android` is `kmp_main_platform android` (only
  `:android_android`, Kotlin module `android`); `app/main/jvm` is a JVM-only module whose leaf is `src/main` and
  `src/test`.
- **commonMain metadata check** (`kmp/metadata.bzl`): every module with native targets gets `:<name>_metadata`,
  commonMain compiled alone (konan's metadata compiler, the native plugins, `-Werror`) against its deps' common
  metadata, so an API only some platforms have (`java.util.regex`, `RegexOption.DOT_MATCHES_ALL`) fails even though
  every platform compile passes. `maven_kmp`'s `metadata` platform is each library's common metadata jar, limited to
  the source sets all our targets' variants include (and those source sets' dependencies), plus the stdlib's and
  kotlin-test's.

## Compiler plugins and generated code

- **Plugins** (`kmp/plugins`): `kmp_library(plugins = ["serialization", "compose", "metro", "mappie"])` and
  `kmp_test(plugins = [… "mokkery"])` name plugins. Each name applies the embeddable jar KGP uses on every platform
  (`kt_compiler_plugin` with `target_embedded_compiler` on the JVM, `-Xplugin`/`-P` on konanc), with the options its
  Gradle plugin passes and the runtime it adds (Metro's as an export, mappie-api, mokkery-runtime for tests). Metro
  runs before Compose and serialization (`-Xcompiler-plugin-order`). Plugin options may use `{temp}`, a directory of
  the compile's own (Mappie keeps state there).
- **Metro across modules**: contribution hints (`metro/hints`) reach the graph's compile through ordinary `deps`, in
  jars and klibs alike, because ABI jars stay off. A dep that exists only so Metro sees it (app/di/core's feature DI
  modules) is a plain dep marked `# keep`, or named with `# gazelle:kmp_keep_dep`, which also exempts it from the
  unused-deps check.
- **SQLDelight** (`kmp/sqldelight`): `sqldelight_library` runs the SQLDelight compiler (`SqlDelightEnvironment`, with
  its defaults, but `generateAsync`, since the browser's worker driver can't block) over a module's `.sq` files, upstream databases' folders as dependencies, and
  `kmp_library(sqldelight = …)` compiles the result as `commonMain` (plus the runtime as an export). konanc gets the
  sources in the module's own compile; on the JVM they compile as `<name>_sqldelight_jvm`, exported, because
  rules_kotlin can't put srcjar sources in a fragment. The compiler API is internal:
  `//bazel/kmp/sqldelight/tests:golden_test` pins its output (refresh with `:update_golden` after checking a bump).
  `commonMain` and the leaf are always named, empty or not (K2 takes a lone common fragment for the platform's own).
- **Generated constants** (`kmp/constants`): native has no classpath resources, so data a module ships is compiled
  into commonMain: `kotlin_constants`, joined with `# gazelle:kmp_generated_srcs :<target>` (the app version from
  `VERSION`, the crypto catalog, the default Google OAuth clients). The OAuth clients are XOR-obfuscated in the
  `@google_oauth` repository (from `GOOGLE_OAUTH_CLIENT_ID`/`_SECRET` and the browser's `GOOGLE_OAUTH_WEB_CLIENT_ID`,
  else `secrets/google-oauth.properties`), so the plaintext is never an action input.
- **Bundled SQLite** (`utils/sqlite3`, `kmp/sqlite`): `@sqlite_amalgamation` downloads the amalgamation
  `sqlite_amalgamation.version(…)` pins in `MODULE.bazel` (SHA-256 checked), `kmp_static_library` compiles it per target with
  konan's own clang and `llvm-ar`, and `kmp_cinterop` embeds the archive in a klib (`-staticLibrary`).
  `kmp_library(cinterops = …)`, set by `# gazelle:kmp_cinterops`, compiles a module with it, even one with no Kotlin
  of its own. `app/db/core` keeps the dep with `kmp_keep_dep`: nothing imports SQLite, it's linked.

## Gazelle

`bazel run //:gazelle` (`gazelle/kmp`, Go) writes every module's `BUILD.bazel`; CI fails on `--mode=diff`, and
`bazel run //:format` runs it first. Never edit a generated rule by hand: change the sources or a directive.

- A module is its directory's `src/<sourceSet>/kotlin` dirs (plus `src/commonMain/sqldelight`). Imports become
  `deps` per platform, resolved by package to a module or to the `@maven_kmp` library whose files declare it (the
  lockfile lists each library's packages). Plugins are inferred (`@Serializable`, `@Composable`, Metro, Mappie and
  Mokkery imports).
- A package several libraries declare (`androidx.compose.runtime`) resolves to one a directive names, to nothing when
  those already bring every candidate, then to the one whose API brings the rest, and only then to the most specific.
  An import resolves to a library every compilation of its source set has, else per compilation (commonMain's
  Material 3 import is `compose_material3_desktop` on the JVM and `androidx_compose_material3` on Android),
  preferring `artifacts` libraries over the modules they bring. Split that way, `deps` has none of them, so
  `metadata_deps` gives `:<name>_metadata` the one the common metadata resolves (`compose_material3`). `android.*` is the SDK.
- Directives, for what sources can't say, are per module and not inherited: `kmp_generate true`, `kmp_targets`,
  `kmp_export <label> [sourceSet]` (an API dep: always a dep, and exported), `kmp_keep_dep <label> [sourceSet]` (a dep
  no import explains: Metro contributors, runtime-only libraries), `kmp_plugins`, `kmp_sqldelight_package`,
  `kmp_sqldelight_dep`, `kmp_cinterops`, `kmp_generated_srcs`, `kmp_test_size`, `kmp_test_data`,
  `kmp_test_jvm_flags`, `kmp_test_flaky`, `kmp_android_host_tests`, `kmp_web_tests`, `kmp_device_test_srcs`,
  `kmp_device_test_resources`, `kmp_main_platform`, and the checks below. `# keep` on a dep keeps it too.
- A run fails when two modules' main sources declare one package (one package, one module).
- `//bazel/gazelle/kmp:generation_test` holds golden workspaces (`testdata/<case>`, `BUILD.in` → `BUILD.out`; refresh
  with `UPDATE_SNAPSHOTS=true bazel run` on it).

## Checks

- **Module checks** (`kmp/checks`), as Gazelle directives, fail `bazel build`. `kmp_visibility` limits
  `app/model/repository/write` to the modules that may take a write repository, and `kmp_forbid_reference` /
  `kmp_allow_reference` keep the engine's own module to `ImportEngineImpl.kt` (comment lines may name one).
  `kmp_forbid_deps`: an aspect fails analysis, per platform and for the tests too, when a pure importer reaches
  `app/db/core`, `app/di/core` or `app/ui/core`, naming the path. A module with `kmp_visibility` also gets a
  testonly, public `:<name>_for_tests`, which Gazelle gives every test that imports it: the rule is about main
  sources, and only a testonly target can depend on it.
- **Dependency hygiene** (`kmp/checks/unuseddeps`): the toolchain runs rules_kotlin's strict deps as an error (a
  class used from a dep that isn't declared, or only exported by one that is, fails the compile; an export is fine),
  and every module and test gets `:<name>_unused_deps`, which fails `bazel build` when its JVM compile's jdeps used
  none of a declared dep's own jars (not what it re-exports). Exports and `keep_deps` are exempt: Metro graphs,
  SQLDelight and other generated code use them where jdeps can't see.
- **Lint** (`kmp/lint`): every `kmp_library`/`kmp_test` gets `:<name>_ktlint` and `:<name>_detekt`, tests that run
  the tool's own `main` over the hand-written sources (generated ones are left out). ktlint is `@ktlint_cli`'s engine with `ktlint_official` and the tree's `.editorconfig` files: the sandbox isn't under the repo, so
  each test carries its package's own and `//:editorconfigs` (those no module owns, such as `app/presenter`'s) at
  their real paths. detekt reads `detekt.yml` over its defaults, without type resolution. `bazel run //:format` is
  Gazelle, then ktlint -F over the same sources. The tools resolve on their own classpaths
  (see **Maven tools**).

## Maven (`maven_kmp`)

rules_jvm_external reads POMs, which name no KMP platform's files, so `kmp/resolver` (Go) follows each library's
Gradle Module Metadata (the `.module` files Maven Central carries) to its per-platform variant: the `-jvm` jar, the
`-linuxx64` klib plus any cinterop klibs, the `androidJvm` AAR.

- Highest version wins, raised by `dependencyConstraints` and BOMs (`platform(…)`, module metadata or POM) without
  bringing anything in. `forces` pins a module everywhere;
  `classpath_forces` pins on the JVM and in metadata only, never in native klibs. `excludes` drops a module everywhere, also
  where a variant points (`available-at`) at it.
- An api-variant dependency the runtime variant leaves out is compile-only (`compile_only_deps`, a neverlink twin),
  and a library with only a runtime variant (the Compose Gradle plugin jar) compiles against that one.
- The `android` platform takes a library's `androidJvm` variant (an AAR: `classes.jar` merged with its `libs/` jars),
  else a JVM one, never androidx's `*Stubs`; `library_platforms` limits a library to the platforms that use it (the
  `-desktop` jars to `jvm`, the androidx roots to `android`).
- The browser platforms (#84) take a library's `kotlin-api` klib like native does: `wasmJs` is Kotlin/Wasm's `js`
  target (never WASI), `js` Kotlin/JS's IR compiler. Their hub targets are `kt_web_import`/`kt_web_group`
  (`kmp/web.bzl`, `WebKlibInfo`). Unlike native they also follow the `kotlin-runtime` variant, as a program links
  its runtime closure: ktor's wasmJs runtime variant alone brings kotlinx-browser. A runtime dependency with no klib
  (Compose's `skiko-js-wasm-runtime`, the program's own JavaScript and wasm) is left out.
- A browser klib names the npm packages it needs in its `package.json` (kotlinx-datetime: `@js-joda/core`; ktor:
  `ws`), which KGP installs with Yarn. The resolver records them per klib (`npm` on its entry) and pins each one, and
  what it depends on (required peers too, as npm 7 does), from registry.npmjs.org by tarball URL and SRI `integrity`
  into the lockfile's `npm`. `maven_kmp.install(npm = {alias: "name@version"})` adds packages our own code imports
  (`@JsModule`), as hub targets per browser platform (`@maven_kmp//:js_joda_timezone`), and `npm_forces` pins a
  package whoever asks for it, as Yarn's `resolutions` does. Only exact versions resolve: a range needs a force.
  Each package is an `http_archive` with an `npm_package` target, carried along `WebKlibInfo.npm` to the programs.
  Our own JavaScript is an `npm_package` too, wrapped per browser platform in a `kt_web_group` with the packages it
  imports (`//app/db/worker`), so a module depends on it like a library (`# gazelle:kmp_keep_dep … webMain`).
- `js` klibs compile with `-target es2015`, as the program links: the frontend then checks `@JsModule` declarations
  as ES imports (a UMD module would also need `@JsNonModule`).
- Common metadata comes in two views. `metadata` holds the source sets that JVM, Android and native share;
  `metadataWeb` holds those the web platforms share too, so it's never wider (no `concurrentMain` in
  kotlinx-coroutines). Only modules that compile for the browser compile their `commonMain` against
  `metadataWeb`, so adding web targets never takes an API away from a module without them. A platform a library
  doesn't publish, or publishes as another module (JetBrains Compose's Android variant is AndroidX's), doesn't narrow
  either view: no module using the library compiles for it, so Compose's metadata still has `skikoMain`.
- The Kotlin stdlib comes from the toolchain. Each
  `maven_kmp.install(name = …)` is its own resolution and hub repository. `artifacts` maps each library's alias to its
  `group:artifact:version`, written in `MODULE.bazel` with the platforms and repositories. A version several
  coordinates share is a constant there (`KTOR = "…"  # renovate: io.ktor:ktor-client-auth`), so they move together,
  as a Gradle `version.ref` did; Renovate's regex manager bumps the constant.
- Each install writes `bazel/kmp/deps/<name>.lock.json` (every file with its sha256); after changing an install, run `bazel run @<name>//:pin`, or `bazel/renovate/repin.sh` for all of them. `//bazel/kmp/deps:maven_kmp_lock_test`
  fails while any lock is stale. Modules name a library by its alias, `@maven_kmp//:kotlinx_io_core` (the
  `_jvm`/`_<target>` suffix is added per platform); `//bazel/kmp/deps` only holds `kotlin_test` and the compiler
  plugins. `dependency_snapshot.py` turns the lockfiles into GitHub's dependency graph.
- `aliases` adds a hub target under a name some tool expects instead of ours: Android Studio builds
  `@maven_kmp//:androidx_compose_ui_ui_tooling` (rules_jvm_external's name) after every sync of a Compose project.

## Maven tools (rules_jvm_external)

The JVM tools the build runs are plain Maven artifacts, so they need no variant resolution: each is a
rules_jvm_external `maven.install` in `MODULE.bazel` (`@detekt_cli`, `@ktlint_cli`, `@dokka`, `@schemaspy`,
`@proguard`, `@android_tools`, `@android_lint`), its own resolution and hub, with the versions written right there and
bumped by Renovate. Targets are rules_jvm_external's names (`@ktlint_cli//:com_pinterest_ktlint_ktlint_cli`).

- Each pins into `bazel/maven/<name>_install.json`. After changing an install run `REPIN=1 bazel run @<name>//:pin`
  (or `bazel/renovate/repin.sh`); a lockfile its install no longer matches fails the build.
- Maven keeps the version a POM asks for, often years old, and doesn't read Gradle's module metadata, which moves a KMP
  library's `-jvm` artifact and the Kotlin stdlib up. So an install also lists those transitive libraries, under
  "Transitive versions held at the newest", and `version_conflict_policy = "pinned"` makes them win.
- A new lockfile can't be bootstrapped with `lock_file` set (the extension reads it first): pin without it, move
  `rules_jvm_external++maven+<name>_install.json` to `bazel/maven/<name>_install.json`, then add `lock_file`.

## Kotlin/Native

The `kotlin_native` module extension (`kmp/native`) downloads an exec host's `kotlin-native-prebuilt` and every konan
dependency it would fetch into `~/.konan` (LLVM, GCC sysroots, msys2, libffi), all sha256-pinned in `pins.bzl`, into
`@kotlin_native_<host>` with konan's data dir inside it: one repository per exec host, fetched only once a toolchain on
that host is selected. Rules call konan through `run_konan` (`toolchain.bzl`), which
passes `airplaneMode=true`, so nothing is fetched at build time. One toolchain per exec host carries every target it
builds (Linux and Windows hosts: linuxX64, linuxArm64, mingwX64; macOS adds macosArm64; Linux also compiles macosArm64
klibs, from the macOS distribution's platform klibs, but can't cinterop or link them: `KLIB_ONLY_TARGETS`), picked by name rather than
by platform, which is how a Linux x64 host cross-links for `//bazel/platforms:linux_arm64`: `kmp_test`'s `:<name>_<target>_cross`
(manual, for linuxArm64 and mingwX64: `CROSS_PLATFORMS`) transitions that target's test binary to its platform, so CI
links them in its one `bazel build` and only runs them on an arm64 and a Windows runner.

Test binaries link against **compiler caches**, as KGP's debug binaries do: without one, konan compiles every library a
binary links (stdlib and every Maven library included) to machine code in each link, minutes per test binary. An
aspect on the test binary's deps (`_cache_aspect`, `native.bzl`) builds one static cache per klib (`KotlinNativeCache`),
and the toolchain carries stdlib's and the platform klibs' (`kotlin_native_platform_cache`; `CACHED_PLATFORM_LIBS` in
`properties.bzl` lists the platform klibs our code uses). The link finds them through `-Xcache-directory`. konan only
caches a target on its own host (`cacheableTargets`: linuxX64 on Linux, macosArm64 on macOS), and the aspect resolves
its own exec platform, so under `rbe-mac` klibs compile on BuildBuddy and their caches build on the Mac. Cross-linked
and Windows test binaries link uncached, as does anything depending on clikt-mordant (`_UNCACHEABLE_KLIBS`).

`kmp_native_executable` links the release binary per target (`-opt`, the module's klib `-Xinclude`d, no compiler
caches: Clikt's two klibs both define a private `Context.selfAndAncestors()`) as `:<name>_<target>`, all `manual`;
`:<name>` is the build platform's own, so `bazel build //...` links only the host's `mm`. `release_assets` adds the
release's `mm-<os>-<arch>` (`pkg_tar` keeping the exec bit; a copied `.exe` on Windows). `jvm_distribution`
(`kmp/distribution.bzl`) is `mm-jvm/bin/` start scripts and `mm-jvm/lib/`, module jars named after their path
(`app-db-core-jvm.jar`).

## Browser (`wasmJs`, `js`)

The web app (#84) compiles for Kotlin/Wasm (`wasmJs`) and Kotlin/JS (`js`) in `web.bzl`. konan's compiler jar carries
the Kotlin/JS and Kotlin/Wasm compilers too, so `run_kotlinc` (`toolchain.bzl`) runs them as it runs the metadata
compiler (`kotlinc-js`, `kotlinc-wasm`; `KonanLauncher` maps each to its compiler class), with the native compiler
plugins. Nothing else is downloaded: the browser stdlib and kotlin-test are their Maven klibs at the toolchain's
version (`@maven_kmp`, resolved like the metadata ones).

A module lists its browser targets in `web_targets`, and each compiles to `:<name>_wasmJs` / `:<name>_js` over
`common ← nonJvm ← web ← wasmJs|js`: `webMain` is what both browser targets share, and `nonJvmMain` (named only when a
module has it) is what they share with native, which then compiles `common ← nonJvm ← native ← …`. A module with web
targets compiles its commonMain metadata against `metadataWeb` (see Maven) with `-Xtarget-platform=…,JS,WasmJs`, and
`:<name>_metadataWeb` names that compile for the web modules that depend on it. `kt_web_executable` links a module's
klib into a production program (`-Xir-dce`): ES modules, plus the `.wasm` and its glue on wasmJs.
`//bazel/kmp/web/tests` builds and tests a module with every source set, and links a program, for both targets.
Gazelle writes `web_targets` from `# gazelle:kmp_targets … wasmJs js` (`web` is both), maps `nonJvm`/`web`/`wasmJs`/`js`
`Main` and `Test` dirs to their attributes, and resolves an import per browser target when no library has it for
both. The DOM (`kotlinx.browser`, `org.w3c`) is `@maven_kmp//:kotlinx_browser`, not the stdlib's: Kotlin 2.4's
wasm stdlib has none, and on js it brings `kotlin-dom-api-compat`, which KGP would add by itself. `# gazelle:kmp_web_tests false` keeps a module's tests
off the browser targets when they need what a browser lacks (`utils/files`: a file system; it builds for the browser
only so its dependents can, and the browser app never calls it).

`kmp_test`'s `web_targets` link the test klib (unoptimised) into `:<name>_<target>_bin/test.mjs`, and `:<name>_wasmJs`
/ `:<name>_js` run it in Node on every host. Node is rules_nodejs' toolchain only, pinned in `MODULE.bazel` with each
platform's SHA-256 (no npm): Renovate bumps `NODE`, and `bazel/renovate/repin.sh` rewrites the SHA-256s from
nodejs.org's `SHASUMS256.txt` (`repin_node.py`). A test program's npm packages are laid out as npm installs them, in
`:<name>_<target>_bin_node_modules/node_modules` (symlinks), and the runner resolves the program's bare imports from
there (`module.registerHooks`). It also gives the program a global `require`, which kotlinx-io's js file system loads
Node's modules with. `testing/web/runner.mjs` defines the `describe`/`it` globals kotlin-test's adapter
registers tests with, runs them (awaiting async ones) and prints TeamCity service messages, which `KnTestLauncher
--node` turns into `test.xml` exactly as it does a native test binary's; `--test_filter` works the same way.

Both links fail on any symbol they can't link, as native's does. A link passes every klib before the klibs it depends
on (a topological depset), because the linker takes a class from the first library that declares it: on js,
kotlinx-browser redeclares `kotlin-dom-api-compat`'s DOM classes with the overloads libraries are compiled against
(`EventTarget.addEventListener(String, (Event) -> Unit)`, which Compose and ktor call and 2.4's dom-api-compat lacks).

**The app** (`app/main/web`) is `kmp_main_platform web`: its `src/main` compiles for the browser targets alone, and
`kt_web_executable` links it once per target. `kt_web_dist` lays out the site (`:dist`): `index.html` and `loader.mjs`
(`site/`) at the root, each program in `wasmJs/` or `js/` with Compose's `skiko-js-wasm-runtime` jar unpacked beside it
(`skiko.mjs`, `skiko.wasm`; the jar is a `jvm`-only library in `@maven_kmp`, and Skiko's klibs are forced to the same
`SKIKO` version), and the programs' npm packages under `node_modules/`. Browsers don't resolve bare imports and a worker
can't use an import map, so `web/dist.mjs` rewrites each bare import to a relative path, resolved as a bundler would
for the browser (`exports` with the `browser` condition, else `module`, else `main`). The loader picks wasmJs when the
browser validates a tiny WasmGC module, js otherwise (`?target=js` forces it). `bazel run //app/main/web:serve -- 8080`
serves the site on 127.0.0.1 with Node (`web/serve.mjs`), and Pages publishes it at `<pages-url>/app/`. Every path in
it is relative, so it runs under any prefix.

## Desktop

- **Compose UI**: `app/ui/*`, `utils/compose/*` and `test/app/ui` are JVM-only (no native `kmp_targets`). JVM
  classpath resources are one jar per compilation (`kmp_resource_jar`, `kmp/resources.bzl`),
  `src/{common,jvmAndroid,jvm}{Main,Test}/resources` merged with the later source set winning a clash.
  `kmp_test_jvm_flags` are the headless system properties, `kmp_test_flaky true` retries (`flaky`, up to 3 runs). They run on BuildBuddy
  too: its executors use our image (`bazel/platforms/rbe`), which adds the libGL, X11 and fontconfig Skiko loads.
  `compose.desktop.currentOs` is `//bazel/kmp/deps:compose_desktop_current_os`, a `select` on the
  target OS and CPU, kept on jvmTest with `kmp_keep_dep`. Test JVMs set `skiko.data.path` under `TEST_TMPDIR`, since
  the sandbox keeps `~/.skiko` read-only.
- **The app and ProGuard** (`kmp/proguard`): `//app/main/jvm:app` is a hand-written `java_binary` adding the runtime-only
  deps (the CLI, `currentOs`, log4j). `proguard_jars` shrinks each runtime jar into its own (`-injars`/`-outjars` per
  jar), with the target JDK's jmods (`//bazel/kmp/deps:jdk_jmods`) as library jars, Compose's
  `default-compose-desktop-rules.pro` (read from the Compose Gradle plugin jar, the `@proguard` install), then
  `proguard-rules.pro`. `jar_filters` drops sqlite-jdbc's other platforms' natives. `:release` runs the shrunk app and
  `:release_service_providers_test` checks its `META-INF/services`; both are `manual`, and
  `--define=proguard_optimize=false` skips the optimizer.
- **Installers** (`kmp/jpackage`): `jlink_runtime`, `jpackage_app_image` and `jpackage_installer`, with the arguments
  Compose's packaging passes. `JPackageRunner` lays out jpackage's input as Compose does (skiko's natives unpacked
  next to the jars, macOS's `Info.plist` and ad-hoc signing on Apple Silicon) and reads Compose's default icons and
  entitlements from the plugin jar. jpackage packages only for its host: `//app/main/jvm:installer` is a deb, dmg or
  msi by OS (WiX 3.11 is `@wix`), and its action runs locally (dpkg-deb/fakeroot, hdiutil, WiX).
  `--define=bundle_native_cli=true` adds the host's native `mm` under `lib/cli`. `install_smoke_test.sh` installs it
  and starts the app, `mm-jvm` and `mm`.

## Android (`kmp/android`)

- **SDK**: the `android_sdk` extension finds the SDK as AGP does (`ANDROID_HOME`, `ANDROID_SDK_ROOT`,
  `local.properties`) and takes the `compile_sdk` platform `android_sdk.levels(…)` sets, never downloading it (CI runs
  `sdkmanager`). `@android_sdk//:lint_sdk` is that platform laid out as an SDK for lint. AGP's own tools come from the
  `@android_tools` install; aapt2 is downloaded as AGP does, for the release `android_sdk.agp(version = …)` names (`pins.bzl`, from AGP's `aapt2_version.properties`).
- **Libraries**: `# gazelle:kmp_targets android …` gives a module `:<name>_android`: `commonMain ← jvmAndroidMain ←
  androidMain` against `@android_sdk//:android_jar` (neverlink), on the JVM's toolchain. `jvmAndroidMain`'s deps are
  `jvm_android_deps`/`jvm_android_exports`, which both compiles see. An `androidMain` runtime-only dep is
  `kmp_keep_dep … androidMain` (the cryptography provider). Resources, manifests and dexing are the app's.
- **Host tests**: `kmp_test(android = True)`'s `:<name>_android` compiles `commonTest ← jvmAndroidTest ←
  androidHostTest` against the module's `_android` and `android.jar`, run by `KmpJUnitRunner` on the mockable jar
  (`:mockable_android_jar`, AGP's own `MockableJarGenerator`, returning default values). Gazelle sets it on every
  Android module with tests; one whose tests need a device says `# gazelle:kmp_android_host_tests false`.
- **APKs** (`apk.bzl`): `android_apk` runs AGP's tools over the runtime classpath's AARs (`aar_import`, `AarInfo`:
  `maven_kmp` writes one per AAR): the manifest merger, `aapt2 compile`/`link`, the R class writer (non-final ids),
  D8 (debug) or R8 (release: the AARs' and jars' keep rules in classpath order, resource shrinking,
  `aapt2 convert`/`optimize` with shortened paths, the AARs' baseline profiles expanded, rewritten and compiled to
  `assets/dexopt/`), then ApkFlinger, with AGP's java-resource excludes/merges and 16K-aligned native libraries.
  `:debug` is debug-signed, `:release` unsigned, `:release_debug_signed` the smoke test's. `--stamp` adds
  `META-INF/version-control-info.textproto` (`bazel/stamp`, `STABLE_GIT_COMMIT`).
- **Device tests** (`device_test.bzl`): `kmp_test`'s `:<name>_device`. `androidDeviceTest` is its own compilation
  (commonTest's deps don't reach it), so `device_srcs` are `src/androidDeviceTest/kotlin` plus what
  `# gazelle:kmp_device_test_srcs` shares, with `device_deps`, `device_manifest` and `device_resources`
  (`kmp_device_test_resources`); the test runner is `kmp_keep_dep … androidDeviceTest`. `android_apk(test_runner = …)`
  builds the self-instrumenting test APK (`com.moneymanager.<module path>.test`, targetSdk = compileSdk), and
  `android_device_test` runs it with `adb install` and `am instrument -w -r` (`DeviceTestRunner`, which writes
  `test.xml`) on the device adb picks (`ANDROID_SERIAL` among several). They're `manual`, `local` and `exclusive`,
  tagged `android_device`, so `bazel test //...` leaves them out; run them with
  `bazel test $(bazel query 'attr(tags, android_device, tests(//...))')` against an API 36 emulator (Espresso 3.5.0
  fails on API 37). rules_android's emulator rules aren't used: they can't take APKs built outside them. The release
  smoke test is `//app/main/android-smoketest:smoketest_test` (its own test APK, the release variant not
  debuggable), which installs `//app/main/android:release_debug_signed` first.
- **Lint** (`lint.bzl`): `//app/main/android:lint` runs the lint AGP does (`@android_lint`'s lint-gradle,
  always AGP + 23) over a lint model built from the APK's `AndroidAppInfo`:
  namespace, SDK levels, merged manifest, classes and R.jar, and every AAR extracted (its `lint.jar`, `public.txt`,
  `annotations.zip`), with the module's `disable` list. Not in its model: build-tools' `core-lambda-stubs.jar` and
  D8's backported-methods list (both only quiet NewApi on desugared calls).

## Coverage, tools and Pages

- **Coverage**: `bazel test --config=coverage $(bazel query 'attr(name, "^test_(jvm|android)$", tests(//...))')`,
  then `bazel/kmp/testing/coverage.sh coverage.xml` (pass the same Bazel flags, such as `--config=rbe`). The JVM and
  Android host tests run under the IntelliJ coverage engine, Kover's JVM agent (`CoverageAgent` starts it), and each
  leaves a binary report in its `test.outputs`. The agent doesn't rewrite jars, so only the test actions change.
  `coverage.sh` merges the reports over the production modules' `_jvm`/`_android` jars (not `test/` fixtures, tests or
  SQLDelight code) with `kover-cli` into one XML report in JaCoCo's format. It names files by package, and Codecov maps them onto
  the repository's paths. A module's code is credited to every test
  that runs it. Native and device tests aren't covered: nothing instruments them. Don't use `bazel coverage`, which
  is JaCoCo.
- **Tools**: `tools/*` and `app/db/schemaspy` are ordinary modules plus `java_binary`s.
  `bazel run //tools/strategy-catalog:generate_bundled_currencies` and `bazel run //tools/crypto-dataset:generate`
  write into the workspace (`BUILD_WORKSPACE_DIRECTORY`).
- **Pages** (`pages/`): `//webpage:site` is the artifact `static.yml` uploads: `webpage/*.html` with SchemaSpy's
  schema docs (`database/`; its action runs locally, since it needs the host's Graphviz), the strategy catalog,
  `kdoc/` and the browser app (`app/`, `//app/main/web:dist`). `modules-graph.md` is written from `bazel query` (`pages/module_graph.py`).
- **KDoc** (`kmp/dokka`): every `kmp_library` has a manual `:<name>_dokka`, and `//webpage:kdoc` aggregates the
  modules `webpage/kdoc_modules.bzl` lists (no schemaspy, `app/main/*`, `test/*` or `tools/*`), so a new module joins
  there. The Kotlin analysis goes on Dokka's plugins classpath beside dokka-base (it reads enum members' docs from
  there), and Dokka loads every plugin its own classpath has, so the runner carries only the CLI. JVM+Android-only
  common code and `jvmAndroidMain` are analysed against the JVM classpath; Apple source sets are analysed against
  the Linux klibs off macOS.

## CI and remote execution

Every workflow sets Bazel up through `.github/actions/bazel-setup` (bazelisk, disk and repository caches, the Android
SDK platform, and `user.bazelrc`). CI uses BuildBuddy: the Linux jobs run every action on its executors
(`--config=rbe`), Windows builds on its runner with its remote cache (`--config=cache`), and macOS
(`--config=rbe-mac`) builds on its runner too except for Kotlin/Native klib compiles: `kt_native_klib` requires the
`compiles_klibs` exec constraint, which the bare host `rbe-mac` lists first lacks, so klibs resolve the Linux toolchain
on `rbe_linux_x64` and `--strategy=KotlinNativeCompile=remote` runs them there, sharing the Linux job's cache. To use it
locally, copy `user.bazelrc.example` to `user.bazelrc` and put your API key in `~/.bazelrc`
(`common --remote_header=x-buildbuddy-api-key=…`). Without a key, pass neither config: invocations would still stream
to BuildBuddy, anonymously. The executors run our image, `bazel/platforms/rbe/Dockerfile`: BuildBuddy's Ubuntu 24.04
one plus Skiko's libraries (so the Compose UI tests run remotely) and lld (a C toolchain configured on a machine with
lld links with it). `.github/workflows/rbe-image.yml` publishes it to ghcr.io from any branch that changes it and
prints the digest that `//bazel/platforms:rbe_linux_x64` pins.
