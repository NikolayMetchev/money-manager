# Kotlin's regex engine, for Kotlin/JS

Kotlin/JS's `Regex` hands a pattern to the browser's RegExp, which rejects Java syntax that saved strategies and
account mappings use (`(?i)`, `\Q…\E`) and reads some of it differently. Kotlin/Native and Kotlin/Wasm instead run
Kotlin's own port of Apache Harmony's `java.util.regex`. These files are that engine, so `PortableRegex` on js reads a
pattern exactly as the JVM, native and wasm builds do.

**Source:** `kotlin-stdlib-wasm-js-2.4.21-sources.jar` (Kotlin v2.4.21, Apache 2.0, see `LICENSE.txt`):
`nativeWasmMain/kotlin/text/regex/**`, `nativeWasmMain/kotlin/text/PatternSyntaxException.kt`, `nativeWasmMain/kotlin/text/Regex.kt`
(as `EngineRegex.kt`) and `wasmCommonMain/kotlin/text/regex/{BitSet,DecompositionHelpers,DecompositionValues}.kt`.
The Apache Harmony headers are kept.

**Changes, kept to what Kotlin/JS needs:**
- One package, `com.moneymanager.regex.engine`; `expect`/`actual` pairs merged into plain declarations.
- `EngineRegex` keeps `Regex`'s matching members, minus the companion and the `RegexOption`/`MatchGroup` declarations
  (Kotlin/JS's own are used; its `MatchGroup` has no `range`).
- `StdlibShims.kt`: the code-point helpers and internals Kotlin/JS's stdlib lacks or hides (`Char.toChars`,
  `Char.toCodePoint`, `digitOf`, …). `assert` is a no-op, as in a Kotlin/Native release build.
- `CharCategory.value` (internal) became `ordinal`, used on both sides of every comparison.
- Syntax newer than our language version rewritten: `val [a, b]` destructuring as `val (a, b)`,
  `@IgnorableReturnValue` and the native opt-ins removed; deprecated `Char.toInt()` became `Char.code`. Upstream's
  `val _ =` is kept where Kotlin 2.5's unused-return-value check needs it.
- Each file carries `@file:Suppress("ktlint", "detekt:all")`, so the build's linters leave upstream's style alone.

When Kotlin is bumped, nothing here has to move: the engine changes rarely. To pick up a fix, copy the new sources
and apply the changes above.
