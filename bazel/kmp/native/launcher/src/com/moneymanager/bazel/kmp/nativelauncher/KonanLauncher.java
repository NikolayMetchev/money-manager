package com.moneymanager.bazel.kmp.nativelauncher;

import java.lang.reflect.Method;
import java.nio.file.Path;

/**
 * Starts a konan tool for {@code run_konan}, in front of konan's own {@code MainKt}.
 *
 * <p>Bazel passes execroot-relative paths, and konan resolves most of them fine, but it matches
 * {@code -Xinclude} against the libraries it resolved by absolute path, so a relative one is never found.
 * Actions have no shell to make it absolute, so this does.
 */
public final class KonanLauncher {
  private static final String INCLUDE = "-Xinclude=";

  private KonanLauncher() {}

  public static void main(String[] args) throws Exception {
    for (int i = 0; i < args.length; i++) {
      if (args[i].startsWith(INCLUDE)) {
        args[i] = INCLUDE + Path.of(args[i].substring(INCLUDE.length())).toAbsolutePath();
      }
    }
    Method main = Class.forName("org.jetbrains.kotlin.cli.utilities.MainKt").getMethod("main", String[].class);
    main.invoke(null, (Object) args);
  }
}
