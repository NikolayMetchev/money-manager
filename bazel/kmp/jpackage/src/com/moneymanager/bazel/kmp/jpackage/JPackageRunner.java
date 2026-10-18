package com.moneymanager.bazel.kmp.jpackage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.spi.ToolProvider;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Compose Desktop's {@code createRuntimeImage}, {@code createReleaseDistributable} and {@code package<Format>}
 * (#1020), for {@code //bazel/kmp/jpackage}. The one argument is a params file, one argument per line; the first
 * is the mode:
 *
 * <ul>
 *   <li>{@code jlink <out> <jlink args…>}: jlink, in process, into {@code out}.
 *   <li>{@code image <out> <options…> -- <jpackage args…>}: lays out the {@code --input} directory as Compose does
 *       (skiko's natives unpacked next to the jars, an empty {@code resources} directory), then jpackage
 *       {@code --type app-image}.
 *   <li>{@code installer <out> <options…> -- <jpackage args…>}: jpackage's installer over an app image.
 * </ul>
 *
 * <p>In the jpackage arguments {@code {version}} is the version file's text, {@code {libs}}, {@code {runtime}},
 * {@code {resources}} and {@code {cli}} the directories this runner lays out, and {@code {icon}} /
 * {@code {entitlements}} Compose's defaults, read from the Compose Gradle plugin's jar on this classpath (so they
 * move with the catalog's Compose). Options: {@code --version-file F}, {@code --jar F} (repeated), {@code --runtime
 * DIR} (a jlinked runtime, copied writable: jpackage changes it in place, and Bazel's inputs are read-only),
 * {@code --cli F} (the native {@code mm}), {@code --app-image DIR}, {@code --wix DIR} (put on jpackage's
 * {@code PATH}, as Compose does on Windows),
 * {@code --name N}, and on macOS {@code --bundle-id B} (else {@code --main-class C}'s package) for the
 * {@code Info.plist}, {@code --mac-package-identifier} and, with Compose's entitlements, {@code --mac-entitlements}.
 *
 * <p>jpackage runs from this JVM's own JDK (the exec toolchain's), so it packages for the host, as it only can.
 */
public final class JPackageRunner {
  private static final OS HOST_OS = OS.current();
  private static final String HOST_ARCH = arch();

  private JPackageRunner() {}

  public static void main(String[] argv) throws Exception {
    List<String> args = Files.readAllLines(Path.of(argv[0]));
    String mode = args.get(0);
    Path out = Path.of(args.get(1)).toAbsolutePath();
    List<String> rest = args.subList(2, args.size());
    switch (mode) {
      case "jlink" -> jlink(out, rest);
      case "image", "installer" -> jpackage(mode, out, rest);
      default -> throw new IllegalArgumentException("unknown mode " + mode);
    }
  }

  private static void jlink(Path out, List<String> args) throws IOException {
    // Bazel creates a tree artifact's directory; jlink refuses to write into an existing one.
    deleteRecursively(out);
    List<String> all = new ArrayList<>(args);
    all.add("--output");
    all.add(out.toString());
    ToolProvider jlink = ToolProvider.findFirst("jlink").orElseThrow();
    int code = jlink.run(System.out, System.err, all.toArray(String[]::new));
    if (code != 0) {
      throw new IllegalStateException("jlink exited with " + code);
    }
  }

  private static void jpackage(String mode, Path out, List<String> args) throws Exception {
    int separator = args.indexOf("--");
    Map<String, List<String>> options = new LinkedHashMap<>();
    for (int i = 0; i < separator; i += 2) {
      options.computeIfAbsent(args.get(i), k -> new ArrayList<>()).add(args.get(i + 1));
    }
    Path work = Files.createTempDirectory("jpackage");
    try {
      Map<String, String> values = new LinkedHashMap<>();
      values.put("{version}", Files.readString(Path.of(one(options, "--version-file"))).strip());
      Path resources = Files.createDirectories(work.resolve("resources"));
      values.put("{resources}", resources.toString());
      values.put("{icon}", composeResource(work, "default-compose-desktop-icon-" + HOST_OS.iconSuffix).toString());
      values.put("{entitlements}", composeResource(work, "default-entitlements.plist").toString());
      if (HOST_OS == OS.MAC) {
        writeInfoPlist(resources.resolve("Info.plist"), options, values.get("{version}"));
      }
      if (options.containsKey("--jar")) {
        values.put("{libs}", layOutLibs(work.resolve("libs"), options.get("--jar"), work).toString());
      }
      if (options.containsKey("--runtime")) {
        Path runtime = work.resolve("runtime");
        copyWritable(Path.of(one(options, "--runtime")), runtime);
        values.put("{runtime}", runtime.toString());
      }
      if (options.containsKey("--cli")) {
        values.put("{cli}", layOutCli(work.resolve("cli"), options.get("--cli")).toString());
      }
      List<String> command = new ArrayList<>();
      command.add(Path.of(System.getProperty("java.home"), "bin", HOST_OS.executable("jpackage")).toString());
      for (String arg : args.subList(separator + 1, args.size())) {
        for (Map.Entry<String, String> value : values.entrySet()) {
          arg = arg.replace(value.getKey(), value.getValue());
        }
        command.add(arg);
      }
      if (HOST_OS == OS.MAC) {
        if (mode.equals("image")) {
          command.add("--java-options");
          command.add("-Xdock:name=" + one(options, "--name"));
        }
        command.add("--mac-package-identifier");
        command.add(bundleId(options));
        command.add("--mac-entitlements");
        command.add(values.get("{entitlements}"));
      }
      if (options.containsKey("--app-image")) {
        Path image = Path.of(one(options, "--app-image")).toAbsolutePath();
        command.add("--app-image");
        // jpackage 18+ wants the .app bundle itself on macOS (Compose passes it the same way).
        command.add((HOST_OS == OS.MAC ? image.resolve(one(options, "--name") + ".app") : image.resolve(
            one(options, "--name"))).toString());
      }
      command.add("--dest");
      command.add(out.toString());
      ProcessBuilder process = new ProcessBuilder(command).inheritIO();
      if (options.containsKey("--wix")) {
        String wix = Path.of(one(options, "--wix")).toAbsolutePath().toString();
        process.environment().merge("PATH", wix, (path, dir) -> dir + ";" + path);
      }
      int code = process.start().waitFor();
      if (code != 0) {
        throw new IllegalStateException("jpackage exited with " + code + ": " + String.join(" ", command));
      }
      if (mode.equals("image") && HOST_OS == OS.MAC) {
        signRuntime(out.resolve(one(options, "--name") + ".app"), Path.of(values.get("{entitlements}")));
      }
    } finally {
      try {
        deleteRecursively(work);
      } catch (IOException e) {
        // A temp directory left behind mustn't hide what happened.
        System.err.println("warning: couldn't delete " + work + ": " + e);
      }
    }
  }

  /** Copies a directory tree, every file left writable by its owner. */
  private static void copyWritable(Path from, Path to) throws IOException {
    try (Stream<Path> walk = Files.walk(from)) {
      for (Path source : walk.toList()) {
        Path target = to.resolve(from.relativize(source).toString());
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        target.toFile().setWritable(true, true);
      }
    }
  }

  /**
   * Compose's {@code prepareWorkingDir}: every jar copied as is, except the host's skiko runtime jar, whose native
   * library (and its {@code .sha256}) is unpacked next to the jars (skiko finds it through {@code
   * skiko.library.path=$APPDIR}). On macOS, dylibs inside jars are ad-hoc signed first, as Compose's unsigned
   * build does on Apple Silicon.
   */
  private static Path layOutLibs(Path libs, List<String> jars, Path work) throws Exception {
    Files.createDirectories(libs.resolve("resources"));
    String skikoJar = "skiko-awt-runtime-" + HOST_OS.id + "-" + HOST_ARCH;
    Set<String> skikoNatives = HOST_OS.skikoNatives(HOST_ARCH);
    for (String jar : jars) {
      Path source = Path.of(jar);
      Path target = libs.resolve(source.getFileName().toString());
      boolean skiko = target.getFileName().toString().startsWith(skikoJar);
      boolean signDylibs = HOST_OS == OS.MAC && HOST_ARCH.equals("arm64");
      if (!skiko && !signDylibs) {
        Files.copy(source, target);
        // Bazel's inputs are read-only, and Windows won't delete a read-only copy.
        target.toFile().setWritable(true, true);
        continue;
      }
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(source));
          ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
        for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
          String name = entry.getName();
          String leaf = name.substring(name.lastIndexOf('/') + 1);
          if (skiko && skikoNatives.contains(leaf.replaceFirst("\\.sha256$", ""))) {
            Files.copy(in, libs.resolve(leaf));
            continue;
          }
          ZipEntry copy = new ZipEntry(name);
          copy.setTime(entry.getTime());
          zip.putNextEntry(copy);
          if (signDylibs && isDylib(name)) {
            Path dylib = work.resolve("sign").resolve(leaf);
            Files.createDirectories(dylib.getParent());
            Files.copy(in, dylib, StandardCopyOption.REPLACE_EXISTING);
            adHocSign(dylib, null);
            Files.copy(dylib, zip);
          } else {
            in.transferTo(zip);
          }
          zip.closeEntry();
        }
      }
    }
    return libs;
  }

  /** {@code --app-content}: jpackage copies the directory into the app under its own name, {@code cli}. */
  private static Path layOutCli(Path cli, List<String> executables) throws IOException {
    Files.createDirectories(cli);
    for (String executable : executables) {
      Path target = cli.resolve(HOST_OS.executable("mm"));
      Files.copy(Path.of(executable), target);
      if (HOST_OS != OS.WINDOWS) {
        Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(target);
        permissions.addAll(Set.of(
            PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE));
        Files.setPosixFilePermissions(target, permissions);
      }
    }
    return cli;
  }

  /** Compose's {@code setInfoPlistValues}, for the options this build sets. */
  private static void writeInfoPlist(Path plist, Map<String, List<String>> options, String version)
      throws IOException {
    String name = one(options, "--name");
    String bundleId = bundleId(options);
    Map<String, String> values = new LinkedHashMap<>();
    values.put("LSMinimumSystemVersion", "10.13");
    values.put("CFBundleDevelopmentRegion", "English");
    values.put("CFBundleAllowMixedLocalizations", "true");
    values.put("CFBundleExecutable", name);
    values.put("CFBundleIconFile", name + ".icns");
    values.put("CFBundleIdentifier", bundleId);
    values.put("CFBundleInfoDictionaryVersion", "6.0");
    values.put("CFBundleName", name);
    values.put("CFBundlePackageType", "APPL");
    values.put("CFBundleShortVersionString", version);
    values.put("LSApplicationCategoryType", "Unknown");
    values.put("CFBundleVersion", version);
    values.put("NSHumanReadableCopyright", "Copyright (C) " + Year.now().getValue());
    values.put("NSSupportsAutomaticGraphicsSwitching", "true");
    values.put("NSHighResolutionCapable", "true");
    try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(plist))) {
      writer.println("<?xml version=\"1.0\" ?>");
      writer.println("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
          + "\"https://www.apple.com/DTDs/PropertyList-1.0.dtd\">");
      writer.println("<plist version=\"1.0\">");
      writer.println("  <dict>");
      for (Map.Entry<String, String> value : values.entrySet()) {
        writer.println("    <key>" + value.getKey() + "</key>");
        writer.println("    <string>" + value.getValue() + "</string>");
      }
      writer.println("  </dict>");
      writer.println("</plist>");
    }
  }

  /** Compose's default bundle id is the main class's package. */
  private static String bundleId(Map<String, List<String>> options) {
    return options.containsKey("--bundle-id")
        ? one(options, "--bundle-id")
        : one(options, "--main-class").replaceFirst("\\.[^.]*$", "");
  }

  /**
   * Compose's {@code modifyRuntimeOnMacOsIfNeeded} without a signing identity: on Apple Silicon every executable
   * and dylib in the bundled runtime, then the runtime and the app themselves, are re-signed ad hoc with Compose's
   * default entitlements (the JIT needs them under the hardened runtime).
   */
  private static void signRuntime(Path app, Path entitlements) throws Exception {
    if (!HOST_ARCH.equals("arm64")) {
      return;
    }
    Path runtime = app.resolve("Contents/runtime");
    List<Path> files;
    try (Stream<Path> walk = Files.walk(runtime)) {
      files = walk.filter(p -> Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)
          && (Files.isExecutable(p) || isDylib(p.getFileName().toString()))).toList();
    }
    for (Path file : files) {
      adHocSign(file, entitlements);
    }
    adHocSign(runtime, entitlements);
    adHocSign(app, entitlements);
  }

  private static void adHocSign(Path file, Path entitlements) throws Exception {
    codesign("-vvvv", "--remove-signature", file.toString());
    List<String> args = new ArrayList<>(List.of("-vvvv", "--sign", "-", "--options", "runtime", "--force"));
    if (entitlements != null) {
      args.add("--entitlements");
      args.add(entitlements.toString());
    }
    args.add(file.toString());
    codesign(args.toArray(String[]::new));
  }

  private static void codesign(String... args) throws Exception {
    List<String> command = new ArrayList<>(List.of("/usr/bin/codesign"));
    command.addAll(List.of(args));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    // codesign -vvvv is chatty: keep its output for a failure only.
    byte[] output = process.getInputStream().readAllBytes();
    if (process.waitFor() != 0) {
      System.err.write(output);
      throw new IllegalStateException("codesign failed: " + String.join(" ", command));
    }
  }

  private static boolean isDylib(String name) {
    return name.endsWith(".dylib") || name.endsWith(".jnilib");
  }

  private static Path composeResource(Path work, String name) throws IOException {
    Path target = work.resolve(name);
    try (InputStream in = JPackageRunner.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IllegalStateException(name + " isn't on the classpath (compose-gradle-plugin)");
      }
      Files.copy(in, target);
    }
    return target;
  }

  private static String one(Map<String, List<String>> options, String name) {
    List<String> values = options.get(name);
    if (values == null || values.size() != 1) {
      throw new IllegalArgumentException("expected one " + name + ", got " + values);
    }
    return values.get(0);
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        // Windows won't delete a read-only file.
        path.toFile().setWritable(true, true);
        Files.delete(path);
      }
    }
  }

  private static String arch() {
    String arch = System.getProperty("os.arch");
    return arch.equals("aarch64") || arch.equals("arm64") ? "arm64" : "x64";
  }

  private enum OS {
    LINUX("linux", "linux.png"),
    MAC("macos", "mac.icns"),
    WINDOWS("windows", "windows.ico");

    final String id;
    final String iconSuffix;

    OS(String id, String iconSuffix) {
      this.id = id;
      this.iconSuffix = iconSuffix;
    }

    static OS current() {
      String name = System.getProperty("os.name").toLowerCase();
      return name.startsWith("mac") ? MAC : name.startsWith("windows") ? WINDOWS : LINUX;
    }

    String executable(String name) {
      return this == WINDOWS ? name + ".exe" : name;
    }

    /** Compose's {@code unpackSkikoForCurrentOS}. */
    Set<String> skikoNatives(String arch) {
      return switch (this) {
        case MAC -> Set.of("libskiko-macos-" + arch + ".dylib");
        case WINDOWS -> Set.of("skiko-windows-" + arch + ".dll", "icudtl.dat");
        case LINUX -> Set.of("libskiko-linux-" + arch + ".so");
      };
    }
  }
}
