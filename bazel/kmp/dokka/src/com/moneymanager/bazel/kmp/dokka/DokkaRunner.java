package com.moneymanager.bazel.kmp.dokka;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Runs Dokka's CLI on a configuration dokka.bzl wrote: `DokkaRunner <config> <VERSION file>`.
 *
 * <p>The configuration is the JSON the Gradle plugin writes (`dokka-configuration.json`), with two
 * placeholders an analysis-time rule can't fill: `@ROOT@`, the action's working directory (Dokka reads
 * and writes absolute paths, as Gradle gives it), and `@VERSION@`, the root VERSION file's contents
 * (Gradle's `project.version`, which the pages show).
 */
public final class DokkaRunner {
    private DokkaRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: DokkaRunner <config> <VERSION file>");
        }
        String root = Paths.get("").toAbsolutePath().toString();
        String version = Files.readString(Paths.get(args[1]), StandardCharsets.UTF_8).trim();
        String config =
                Files.readString(Paths.get(args[0]), StandardCharsets.UTF_8)
                        .replace("@ROOT@", jsonEscape(root))
                        .replace("@VERSION@", jsonEscape(version));
        Path resolved = Files.createTempFile("dokka-configuration", ".json");
        Files.writeString(resolved, config, StandardCharsets.UTF_8);
        // The CLI is a fat jar on the runtime classpath only, so it's called the way `java -jar` would.
        Class.forName("org.jetbrains.dokka.MainKt")
                .getMethod("main", String[].class)
                .invoke(null, (Object) new String[] {resolved.toString()});
        // The Kotlin analysis leaves a non-daemon thread pool behind, which would keep the JVM up forever.
        System.exit(0);
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
