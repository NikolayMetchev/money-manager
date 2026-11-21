"""Instrumented tests on a connected device (#1024), as AGP's `connected*AndroidTest` tasks run them."""

load("@rules_java//java:java_test.bzl", "java_test")

INSTRUMENTATION_RUNNER = "androidx.test.runner.AndroidJUnitRunner"

def android_device_test(name, apk, package, runner = INSTRUMENTATION_RUNNER, install = {}, **kwargs):
    """A test APK's instrumentation, run through adb on the device it picks (`ANDROID_SERIAL` among several).

    With no device connected the test boots the emulator `@android_emulator` downloads, and stops it afterwards.

    The APK is installed, its runner run with `am instrument -w -r` and the output turned into test.xml, then
    it's uninstalled. A device is one shared screen outside any sandbox or executor, so the test is `local` and
    `exclusive`, and tagged `android_device`: .bazelrc leaves that tag out of every `bazel test` unless `--config=device`
    asks for it.

    Args:
        name: the test.
        apk: the test APK (an `android_apk` with `test_runner`).
        package: the test APK's application id.
        runner: its instrumentation runner.
        install: more APKs to install first and uninstall after, application id → label (the app a
            `com.android.test` module drives).
        **kwargs: passed to java_test (size, …).
    """
    extra = []
    for app, label in install.items():
        extra.append("%s=$(rootpath %s)" % (app, label))
    java_test(
        name = name,
        main_class = "com.moneymanager.tools.androiddevicetest.DeviceTestRunnerKt",
        use_testrunner = False,
        args = [
            "$(rootpath %s)" % Label("@android_platform_tools//:adb"),
            "$(rootpath %s)" % Label("@android_emulator//:emulator.properties"),
            "$(rootpath %s)" % apk,
            package,
            runner,
        ] + extra,
        data = [Label("@android_platform_tools//:adb"), Label("@android_platform_tools//:adb_files"), Label("@android_emulator//:emulator.properties"), apk] + install.values(),
        tags = ["local", "exclusive", "external", "android_device"],
        runtime_deps = [Label("//tools/android-device-test:android-device-test_jvm")],
        **kwargs
    )
