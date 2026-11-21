package com.moneymanager.tools.repo

import java.io.File

private const val LICENSE_TEXT = "bazel/kmp/android/sdk_license.txt"
private const val LICENSE_RECORD = "money-manager-sdk-license.txt"

// The Arm system image's license, which adds a clause on running Arm code: only an Arm host's emulator needs it.
private const val ARM_LICENSE_TEXT = "bazel/kmp/android/sdk_arm_license.txt"
private const val ARM_LICENSE_RECORD = "money-manager-sdk-arm-license.txt"

/**
 * Bazel can't ask during a build, so this is where a person accepts the Android SDK License: `bazel run` gives it the
 * terminal. The record is what `bazel/kmp/android/extensions.bzl` looks for before downloading the SDK.
 */
fun acceptAndroidLicense(args: List<String> = emptyList()) {
    // The lookup extensions.bzl's _android_user_home makes, or it wouldn't find the record.
    val home =
        System.getenv("ANDROID_USER_HOME")?.takeIf { it.isNotEmpty() }
            ?: "${System.getenv("HOME") ?: System.getenv("USERPROFILE") ?: ""}/.android"
    val arm =
        when (args.singleOrNull()) {
            null -> false
            "arm" -> true
            else -> fail("usage: accept-android-license [arm]")
        }
    val (text, record) = if (arm) ARM_LICENSE_TEXT to ARM_LICENSE_RECORD else LICENSE_TEXT to LICENSE_RECORD
    val accepted = acceptAndroidLicense(workspaceFile(text), File(home, record), ::println, ::readlnOrNull)
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
