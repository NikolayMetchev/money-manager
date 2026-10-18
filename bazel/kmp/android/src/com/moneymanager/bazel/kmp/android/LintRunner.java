package com.moneymanager.bazel.kmp.android;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs Android lint over an {@code android_lint} model, as AGP's {@code lint<Variant>} does.
 *
 * <p>Arguments: the model's files, {@code --}, then lint's own flags. The model names its paths against
 * {@code @ROOT@} (the runfiles' workspace directory), {@code @TMP@} and {@code @SEP@}; this writes a copy with them
 * filled in, and points lint's cache, partial results and Android user home at the test's temporary directory.
 */
public final class LintRunner {
  private LintRunner() {}

  public static void main(String[] args) throws Exception {
    Path root = Path.of("").toAbsolutePath();
    Path tmp = Path.of(System.getenv().getOrDefault("TEST_TMPDIR", System.getProperty("java.io.tmpdir")))
        .toAbsolutePath();
    Path model = tmp.resolve("lint-model");
    Files.createDirectories(model);
    String sdkHome = null;
    int i = 0;
    for (; i < args.length && !args[i].equals("--"); i++) {
      Path file = Path.of(args[i]);
      String content = fill(Files.readString(file), root, tmp);
      if (file.getFileName().toString().equals("sdk-home")) {
        sdkHome = content.trim();
      } else {
        Files.writeString(model.resolve(file.getFileName()), content);
      }
    }
    System.setProperty("user.home", tmp.toString());
    System.setProperty("ANDROID_USER_HOME", tmp.resolve("android").toString());
    List<String> lint = new ArrayList<>(List.of(
        "--lint-model", model.toString(),
        "--cache-dir", tmp.resolve("lint-cache").toString(),
        "--text", "stdout"));
    if (sdkHome != null) {
      lint.addAll(List.of("--sdk-home", sdkHome));
    }
    String outputs = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR");
    if (outputs != null) {
      lint.addAll(List.of("--xml", Path.of(outputs, "lint-results.xml").toString()));
    }
    for (i++; i < args.length; i++) {
      lint.add(args[i]);
    }
    Class.forName("com.android.tools.lint.Main")
        .getMethod("main", String[].class)
        .invoke(null, (Object) lint.toArray(new String[0]));
  }

  private static String fill(String text, Path root, Path tmp) {
    return text.replace("@ROOT@", slashes(root))
        .replace("@TMP@", slashes(tmp))
        .replace("@SEP@", File.pathSeparator);
  }

  private static String slashes(Path path) {
    return path.toString().replace('\\', '/');
  }
}
