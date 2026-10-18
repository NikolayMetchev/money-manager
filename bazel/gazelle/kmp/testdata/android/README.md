Android modules (#1021). `kmp_targets android` gives `lib` an Android compile: `android = True`, its
`androidMain` sources, and `jvmAndroidMain`'s dependencies in `jvm_android_deps`, which both the JVM and Android
compiles see. An import is resolved to a library every compilation of its source set has (`ui.layout` is only in
`g:ui`, which has both variants); when none has (`ui`: the catalog's `ui-desktop` is the JVM's, `ui-android`
Android's), each compilation gets its own. A catalog library wins over a module one only brings (`g:ui` also
declares `ui`). `android.*` is the SDK's `android.jar`. `desk` has no Android target, so its `androidMain` isn't
compiled (and `ui` resolves to the JVM's library in commonMain).

Android host tests (#1022): an Android module's tests also run as AGP's host tests (`android = True` on the
`kmp_test`), with `androidHostTest`'s sources in `android_srcs` and their imports in `android_deps` (`sqlite`,
which only `androidMain` names). `dev`'s host tests are off (`kmp_android_host_tests false`, Gradle's
`testAndroidHostTest` disabled), so its tests stay JVM-only.

Android device tests (#1024): `androidDeviceTest` is its own compilation, so `dev`'s `kmp_test` gets its
`src/androidDeviceTest/kotlin` sources plus the commonTest ones `kmp_device_test_srcs` shares (Gradle's
`kotlin.srcDir`) in `device_srcs`, their imports in `device_deps` (`kotlin.test` again, since commonTest's deps
don't reach it; `ui.layout` and `sqlite` resolved as on Android), the `kmp_keep_dep … androidDeviceTest` there too,
the source set's `AndroidManifest.xml` as `device_manifest` and `kmp_device_test_resources` as `device_resources`.
