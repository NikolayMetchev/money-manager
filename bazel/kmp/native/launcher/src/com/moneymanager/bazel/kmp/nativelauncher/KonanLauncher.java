package com.moneymanager.bazel.kmp.nativelauncher;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Starts a konan tool for {@code run_konan}, in front of konan's own {@code MainKt}.
 *
 * <p>Bazel passes execroot-relative paths, and konan resolves most of them fine, but it matches
 * {@code -Xinclude} against the libraries it resolved by absolute path, so a relative one is never found.
 * Actions have no shell to make it absolute, so this does.
 *
 * <p>It also replaces {@code {temp}} in any argument with a directory of its own, deleted afterwards, the way
 * rules_kotlin does for JVM plugin options: a plugin that keeps state between compiles (Mappie) then never
 * reads an earlier build's.
 */
public final class KonanLauncher {
  private static final String INCLUDE = "-Xinclude=";

  private KonanLauncher() {}

  private static final String TEMP = "{temp}";

  public static void main(String[] args) throws Exception {
    Path temp = null;
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith(INCLUDE)) {
        args[i] = INCLUDE + Path.of(args[i].substring(INCLUDE.length())).toAbsolutePath();
      }
      if (args[i].contains(TEMP)) {
        if (temp == null) {
          temp = Files.createTempDirectory("konan");
        }
        args[i] = args[i].replace(TEMP, temp.toString());
      }
    }
    try {
      Method main = Class.forName("org.jetbrains.kotlin.cli.utilities.MainKt").getMethod("main", String[].class);
      main.invoke(null, (Object) args);
    } finally {
      if (temp != null) {
        try (Stream<Path> paths = Files.walk(temp)) {
          paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
      }
    }
  }
}
