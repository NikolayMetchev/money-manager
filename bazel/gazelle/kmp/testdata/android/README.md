Android modules (#1021). `kmp_targets android` gives `lib` an Android compile: `android = True`, its
`androidMain` sources, and `jvmAndroidMain`'s dependencies in `jvm_android_deps`, which both the JVM and Android
compiles see. An import is resolved to a library every compilation of its source set has (`ui.layout` is only in
`g:ui`, which has both variants); when none has (`ui`: the catalog's `ui-desktop` is the JVM's, `ui-android`
Android's), each compilation gets its own. A catalog library wins over a module one only brings (`g:ui` also
declares `ui`). `android.*` is the SDK's `android.jar`. `desk` has no Android target, so its `androidMain` isn't
compiled (and `ui` resolves to the JVM's library in commonMain).
