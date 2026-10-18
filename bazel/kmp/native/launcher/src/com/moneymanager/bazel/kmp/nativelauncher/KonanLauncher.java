package com.moneymanager.bazel.kmp.nativelauncher;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
 *
 * <p>cinterop copies each {@code -staticLibrary} into a temporary klib directory and deletes it afterwards. On
 * Windows the copy keeps the read-only attribute Bazel gives its outputs, so the delete fails; cinterop gets a
 * writable copy of each library instead.
 *
 * <p>{@code run_konan} passes the tool's arguments in params files ({@code @file}, one argument per line), as
 * a module's command line can pass Windows' 32767-character limit; this reads them back.
 *
 * <p>The tool {@code kotlinc-metadata} isn't konan's: it is the common metadata compiler, which konan's jar
 * carries too (Gradle's {@code compileCommonMainKotlinMetadata}), run with the same compiler plugins.
 */
public final class KonanLauncher {
  private static final String INCLUDE = "-Xinclude=";

  private KonanLauncher() {}

  private static final String TEMP = "{temp}";

  private static final String METADATA = "kotlinc-metadata";

  private static final String LIBRARY_PATH = "-libraryPath";

  private static final String STATIC_LIBRARY = "-staticLibrary";

  private static String[] expandParamsFiles(String[] args) throws Exception {
    List<String> out = new ArrayList<>();
    for (String arg : args) {
      if (arg.startsWith("@")) {
        out.addAll(Files.readAllLines(Path.of(arg.substring(1)), StandardCharsets.UTF_8));
      } else {
        out.add(arg);
      }
    }
    return out.toArray(new String[0]);
  }

  public static void main(String[] commandLine) throws Exception {
    String[] args = expandParamsFiles(commandLine);
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
      if (args[i].equals(STATIC_LIBRARY) && i >= 2 && i + 1 < args.length && args[i - 2].equals(LIBRARY_PATH)) {
        if (temp == null) {
          temp = Files.createTempDirectory("konan");
        }
        Path dir = Files.createDirectories(temp.resolve("static" + i));
        Path copy = Files.copy(Path.of(args[i - 1], args[i + 1]), dir.resolve(args[i + 1]));
        copy.toFile().setWritable(true);
        args[i - 1] = dir.toString();
      }
    }
    int exitCode = 0;
    try {
      if (args.length > 0 && args[0].equals(METADATA)) {
        exitCode = compileMetadata(Arrays.copyOfRange(args, 1, args.length));
      } else {
        Method main = Class.forName("org.jetbrains.kotlin.cli.utilities.MainKt").getMethod("main", String[].class);
        main.invoke(null, (Object) args);
      }
    } finally {
      if (temp != null) {
        try (Stream<Path> paths = Files.walk(temp)) {
          paths.sorted(Comparator.reverseOrder()).forEach(path -> {
            path.toFile().setWritable(true);
            path.toFile().delete();
          });
        }
      }
    }
    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }

  private static int compileMetadata(String[] args) throws Exception {
    Class<?> cliCompiler = Class.forName("org.jetbrains.kotlin.cli.common.CLICompiler");
    Object compiler = Class.forName("org.jetbrains.kotlin.cli.metadata.KotlinMetadataCompiler").getConstructor().newInstance();
    Object exitCode = cliCompiler.getMethod("doMainNoExit", cliCompiler, String[].class).invoke(null, compiler, args);
    return (Integer) exitCode.getClass().getMethod("getCode").invoke(exitCode);
  }
}
