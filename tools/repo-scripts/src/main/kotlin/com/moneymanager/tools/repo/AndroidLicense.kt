package com.moneymanager.tools.repo

import java.io.File

private const val LICENSE_TEXT = "bazel/kmp/android/sdk_license.txt"
private const val LICENSE_RECORD = "money-manager-sdk-license.txt"

/**
 * Bazel can't ask during a build, so this is where a person accepts the Android SDK License: `bazel run` gives it the
 * terminal. The record is what `bazel/kmp/android/extensions.bzl` looks for before downloading the SDK.
 */
fun acceptAndroidLicense() {
    val home = System.getenv("ANDROID_USER_HOME") ?: "${System.getProperty("user.home")}/.android"
    val accepted = acceptAndroidLicense(workspaceFile(LICENSE_TEXT), File(home, LICENSE_RECORD), ::println, ::readlnOrNull)
    if (!accepted) fail("Not accepted: Android targets won't build.")
}

/** Shows [license] and, on a yes from [answer], records it in [record]. Says whether it was accepted. */
fun acceptAndroidLicense(
    license: File,
    record: File,
    show: (String) -> Unit,
    answer: () -> String?,
): Boolean {
    val text = license.readBytes()
    show(String(text))
    show("\nDo you accept the Android SDK License above? [y/N]")
    if (answer()?.trim()?.lowercase() !in setOf("y", "yes")) return false
    record.parentFile.mkdirs()
    record.writeBytes(text)
    show("Accepted. Bazel will download the Android SDK when a build needs it.")
    return true
}
