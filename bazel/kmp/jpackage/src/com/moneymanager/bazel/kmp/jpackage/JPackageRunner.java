package com.moneymanager.bazel.kmp.jpackage;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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
 *   <li>{@code aot <out> <options…> -- <trainings…>}: an app image with an AOT cache per launcher (#1254); see
 *       {@link #aot}.
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

  static void main(String[] argv) throws Exception {
    List<String> args = Files.readAllLines(Path.of(argv[0]));
    String mode = args.get(0);
    Path out = Path.of(args.get(1)).toAbsolutePath();
    List<String> rest = args.subList(2, args.size());
    switch (mode) {
      case "jlink" -> jlink(out, rest);
      case "image", "installer" -> jpackage(mode, out, rest);
      case "aot" -> aot(out, rest);
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
        // jpackage packages a copy: Bazel's outputs are read-only (codesign then can't re-sign the bundle) and,
        // outside the sandbox, may be symlinks into its cache, which the package would carry as they are.
        Path image = work.resolve("app-image");
        copyWritable(Path.of(one(options, "--app-image")).toAbsolutePath(), image);
        command.add("--app-image");
        // jpackage 18+ wants the .app bundle itself on macOS (Compose passes it the same way).
        command.add((HOST_OS == OS.MAC ? image.resolve(one(options, "--name") + ".app") : image.resolve(
            one(options, "--name"))).toString());
      }
      command.add("--dest");
      command.add(out.toString());
      ProcessBuilder process = new ProcessBuilder(command).inheritIO();
      // jpackage stamps the files it packages with the time it copies them; dpkg-deb clamps every file's mtime to
      // SOURCE_DATE_EPOCH, so the deb installs them at the time the AOT caches were trained against.
      process.environment().put("SOURCE_DATE_EPOCH", Long.toString(PINNED_MTIME.toInstant().getEpochSecond()));
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
      if (mode.equals("installer") && HOST_OS == OS.MAC) {
        try (Stream<Path> dmgs = Files.list(out)) {
          for (Path dmg : dmgs.filter(f -> f.toString().endsWith(".dmg")).toList()) {
            pinDmgMtimes(dmg, work);
          }
        }
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

  /**
   * Every file in an app image (and, on install, every file the installer puts down) carries this modification
   * time. The JVM rejects an AOT cache when a jar on the classpath has another mtime than the one it was trained
   * against, and jpackage, Bazel's remote outputs and copies all restamp files, so the image is trained with its
   * files at this time and each installer is rewritten to install them at it. Whole seconds, even ones, so a
   * cabinet's 2-second DOS times hold it too.
   */
  static final FileTime PINNED_MTIME = FileTime.from(Instant.parse("2020-01-01T00:00:00Z"));

  private static final long TRAINING_TIMEOUT_MINUTES = 10;

  /**
   * Trains an AOT cache (JEP 483/514/515) for each of an app image's launchers, into a copy of the image.
   * Options: {@code --image DIR} (a {@code jpackage_app_image}), {@code --name N} and {@code --database F} (the
   * database each training run opens, a copy each time). After {@code --}, one block per launcher:
   * {@code @launcher NAME}, then {@code @env K=V} lines and the launcher's arguments, one per line.
   * {@code {probe}} in an env value is a file in the run's scratch directory.
   *
   * <p>For each launcher, the cache is built in two steps through the launcher itself, since the jlinked runtime
   * has no {@code bin/java} for {@code -XX:AOTCacheOutput}'s child JVM:
   *
   * <ol>
   *   <li>Record: run the training with {@code -XX:AOTMode=record}, in a home of its own (the JVM's {@code
   *       user.home} as well as {@code HOME}) holding a copy of the database as the last one opened.
   *   <li>Create: run the launcher again with {@code -XX:AOTMode=create}, which writes {@code <launcher>.aot}
   *       next to the jars and exits.
   *   <li>Point the launcher's {@code .cfg} at the cache, with the JVM's warnings on stderr, so a stale cache
   *       can't corrupt the CLI's JSON.
   *   <li>Run the training again with {@code -XX:AOTMode=on}, which fails unless the cache loads.
   * </ol>
   *
   * <p>On Linux without a {@code DISPLAY}, the runs get an Xvfb of their own.
   */
  private static void aot(Path out, List<String> args) throws Exception {
    int separator = args.indexOf("--");
    Map<String, List<String>> options = new LinkedHashMap<>();
    for (int i = 0; i < separator; i += 2) {
      options.computeIfAbsent(args.get(i), k -> new ArrayList<>()).add(args.get(i + 1));
    }
    List<Training> trainings = Training.parse(args.subList(separator + 1, args.size()));
    String name = one(options, "--name");
    Path database = Path.of(one(options, "--database")).toAbsolutePath();
    deleteRecursively(out);
    copyWritable(Path.of(one(options, "--image")).toAbsolutePath(), out);
    pinMtimes(out);
    Path app = appDirectory(out, name);
    Path work = Files.createTempDirectory("aot");
    Process xvfb = null;
    try {
      Map<String, String> display = new LinkedHashMap<>();
      if (HOST_OS == OS.LINUX && System.getenv("DISPLAY") == null) {
        xvfb = new ProcessBuilder("Xvfb", "-displayfd", "1", "-nolisten", "tcp", "-screen", "0", "1280x1024x24")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
        String number = new String(xvfb.getInputStream().readNBytes(16)).strip().split("\\s+")[0];
        display.put("DISPLAY", ":" + number);
      }
      for (Training training : trainings) {
        Path launcher = launcher(out, name, training.launcher);
        Path config = work.resolve(training.launcher + ".aotconf");
        Path cache = app.resolve(training.launcher + ".aot");
        run(training, launcher, database, work, display, "-XX:AOTMode=record -XX:AOTConfiguration=" + config);
        createCache(launcher, work, config, cache);
        pointConfigAtCache(app.resolve(training.launcher + ".cfg"), training.launcher);
        run(training, launcher, database, work, display, "-XX:AOTMode=on");
        Files.setLastModifiedTime(cache, PINNED_MTIME);
      }
      if (HOST_OS == OS.MAC) {
        // The .cfg files and caches are inside the signed bundle.
        Path bundle = out.resolve(name + ".app");
        if (HOST_ARCH.equals("arm64")) {
          adHocSign(bundle, composeResource(work, "default-entitlements.plist"));
        }
      }
    } finally {
      if (xvfb != null) {
        xvfb.destroy();
      }
      try {
        deleteRecursively(work);
      } catch (IOException e) {
        System.err.println("warning: couldn't delete " + work + ": " + e);
      }
    }
  }

  /** One launcher's training run: its name, environment and arguments. */
  private record Training(String launcher, Map<String, String> env, List<String> args) {
    static List<Training> parse(List<String> lines) {
      List<Training> trainings = new ArrayList<>();
      for (String line : lines) {
        if (line.startsWith("@launcher ")) {
          trainings.add(new Training(line.substring("@launcher ".length()), new LinkedHashMap<>(), new ArrayList<>()));
        } else if (trainings.isEmpty()) {
          throw new IllegalArgumentException("training line before any @launcher: " + line);
        } else if (line.startsWith("@env ")) {
          String[] pair = line.substring("@env ".length()).split("=", 2);
          trainings.getLast().env.put(pair[0], pair[1]);
        } else {
          trainings.getLast().args.add(line);
        }
      }
      return trainings;
    }
  }

  /** Runs a launcher's training in a fresh home, with {@code jvmOptions} added; fails unless it exits 0. */
  private static void run(
      Training training, Path launcher, Path database, Path work, Map<String, String> display, String jvmOptions)
      throws Exception {
    Path run = Files.createTempDirectory(work, training.launcher);
    Path home = Files.createDirectories(run.resolve("home"));
    Path data = Files.createDirectories(home.resolve(".moneymanager"));
    Path copy = data.resolve("training.db");
    Files.copy(database, copy);
    makeWritable(copy);
    Files.writeString(
        data.resolve("settings.json"), "{\"lastDatabase\":\"" + jsonEscape(copy.toString()) + "\"}\n");
    List<String> command = new ArrayList<>();
    command.add(launcher.toString());
    command.addAll(training.args);
    ProcessBuilder process = new ProcessBuilder(command)
        .directory(run.toFile())
        .redirectErrorStream(true)
        .redirectOutput(run.resolve("output.txt").toFile());
    Map<String, String> env = process.environment();
    isolate(env, home);
    env.putAll(display);
    for (Map.Entry<String, String> entry : training.env.entrySet()) {
      env.put(entry.getKey(), entry.getValue().replace("{probe}", run.resolve("probe.json").toString()));
    }
    env.put("JAVA_TOOL_OPTIONS", "-Duser.home=" + home + " " + jvmOptions);
    waitFor(process.start(), command, run.resolve("output.txt"));
  }

  /** {@code -XX:AOTMode=create} reads the recorded configuration and writes the cache; the app doesn't run. */
  private static void createCache(Path launcher, Path work, Path config, Path cache) throws Exception {
    Path output = work.resolve(cache.getFileName() + ".create.txt");
    ProcessBuilder process = new ProcessBuilder(launcher.toString())
        .directory(work.toFile())
        .redirectErrorStream(true)
        .redirectOutput(output.toFile());
    isolate(process.environment(), work);
    process.environment().put(
        "JAVA_TOOL_OPTIONS", "-XX:AOTMode=create -XX:AOTConfiguration=" + config + " -XX:AOTCache=" + cache);
    waitFor(process.start(), List.of(launcher.toString(), "(AOTMode=create)"), output);
    if (!Files.isRegularFile(cache)) {
      throw new IllegalStateException(launcher + " wrote no AOT cache:\n" + Files.readString(output));
    }
  }

  /**
   * Adds the cache to the launcher's {@code [JavaOptions]}, with the JVM's own warnings sent to stderr: by default
   * they go to stdout, where a stale cache's would land in the middle of the CLI's JSON.
   */
  private static void pointConfigAtCache(Path cfg, String launcher) throws IOException {
    List<String> lines = new ArrayList<>(Files.readAllLines(cfg));
    int section = lines.indexOf("[JavaOptions]");
    if (section < 0) {
      throw new IllegalStateException(cfg + " has no [JavaOptions]");
    }
    int at = section + 1;
    while (at < lines.size() && !lines.get(at).startsWith("[") && !lines.get(at).isBlank()) {
      at++;
    }
    lines.addAll(at, List.of(
        "java-options=-XX:AOTCache=$APPDIR/" + launcher + ".aot",
        "java-options=-Xlog:disable",
        "java-options=-Xlog:all=warning:stderr"));
    Files.write(cfg, lines);
    Files.setLastModifiedTime(cfg, PINNED_MTIME);
  }

  private static void isolate(Map<String, String> env, Path home) {
    env.put("HOME", home.toString());
    env.put("USERPROFILE", home.toString());
    env.put("APPDATA", home.resolve("AppData/Roaming").toString());
    env.put("LOCALAPPDATA", home.resolve("AppData/Local").toString());
    for (String name : List.of("MM_STARTUP_PROBE", "MM_STARTUP_PROBE_ACCOUNT", "MM_VAULT_PASSWORD",
        "MM_REMOTE_PASSWORD", "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")) {
      env.remove(name);
    }
  }

  private static void waitFor(Process process, List<String> command, Path output) throws Exception {
    if (!process.waitFor(TRAINING_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
      process.destroyForcibly();
      throw new IllegalStateException(String.join(" ", command) + " timed out:\n" + tail(output));
    }
    if (process.exitValue() != 0) {
      throw new IllegalStateException(
          String.join(" ", command) + " exited " + process.exitValue() + ":\n" + tail(output));
    }
  }

  private static String tail(Path output) throws IOException {
    String text = Files.exists(output) ? Files.readString(output) : "";
    return text.length() > 8000 ? text.substring(text.length() - 8000) : text;
  }

  private static String jsonEscape(String text) {
    return text.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  /** The directory holding the jars and launcher {@code .cfg}s. */
  private static Path appDirectory(Path image, String name) {
    return switch (HOST_OS) {
      case MAC -> image.resolve(name + ".app/Contents/app");
      case WINDOWS -> image.resolve(name).resolve("app");
      case LINUX -> image.resolve(name).resolve("lib/app");
    };
  }

  private static Path launcher(Path image, String name, String launcher) {
    return switch (HOST_OS) {
      case MAC -> image.resolve(name + ".app/Contents/MacOS").resolve(launcher);
      case WINDOWS -> image.resolve(name).resolve(launcher + ".exe");
      case LINUX -> image.resolve(name).resolve("bin").resolve(launcher);
    };
  }

  /**
   * Rewrites a dmg with every file in it at {@link #PINNED_MTIME}: jpackage stamps them with the time it copies
   * them. A copy that keeps modification times (Finder's, {@code ditto}, {@code cp -p}) then keeps the AOT caches
   * valid. The modification time isn't part of the code signature.
   */
  private static void pinDmgMtimes(Path dmg, Path work) throws Exception {
    Path writable = work.resolve("writable.dmg");
    Path mount = Files.createDirectories(work.resolve("dmg"));
    run(List.of("hdiutil", "convert", dmg.toString(), "-format", "UDRW", "-o", writable.toString()));
    run(List.of("hdiutil", "attach", "-nobrowse", "-noverify", "-mountpoint", mount.toString(), writable.toString()));
    try (Stream<Path> apps = Files.list(mount)) {
      // Only the bundles: the volume's own .fseventsd isn't ours to touch.
      for (Path app : apps.filter(f -> f.getFileName().toString().endsWith(".app")).toList()) {
        pinMtimes(app);
      }
    } finally {
      run(List.of("hdiutil", "detach", mount.toString()));
    }
    Files.delete(dmg);
    run(List.of("hdiutil", "convert", writable.toString(), "-format", "UDZO", "-o", dmg.toString()));
    Files.delete(writable);
  }

  private static void run(List<String> command) throws Exception {
    int code = new ProcessBuilder(command).inheritIO().start().waitFor();
    if (code != 0) {
      throw new IllegalStateException(String.join(" ", command) + " exited with " + code);
    }
  }

  /** Sets every file's modification time to {@link #PINNED_MTIME} (directories too, which nothing checks). */
  static void pinMtimes(Path root) throws IOException {
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path path : walk.toList()) {
        if (!Files.isSymbolicLink(path)) {
          Files.setLastModifiedTime(path, PINNED_MTIME);
        }
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
        makeWritable(target);
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
    Set<String> skikoNatives = HOST_OS.skikoNatives();
    for (String jar : jars) {
      Path source = Path.of(jar);
      Path target = libs.resolve(source.getFileName().toString());
      boolean skiko = target.getFileName().toString().startsWith(skikoJar);
      boolean signDylibs = HOST_OS == OS.MAC && HOST_ARCH.equals("arm64");
      if (!skiko && !signDylibs) {
        Files.copy(source, target);
        // Bazel's inputs are read-only, and Windows won't delete a read-only copy.
        makeWritable(target);
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
    return values.getFirst();
  }

  private static void deleteRecursively(Path dir) throws IOException {
    if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
        // Windows won't delete a read-only file.
        makeWritable(path);
        Files.delete(path);
      }
    }
  }

  private static void makeWritable(Path path) throws IOException {
    // Windows has no write bit on a directory (setWritable says false) and deletes it regardless.
    if (!path.toFile().setWritable(true, true) && !Files.isDirectory(path)) {
      throw new IOException("can't make " + path + " writable");
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
    Set<String> skikoNatives() {
      return switch (this) {
        case MAC -> Set.of("libskiko-macos-" + HOST_ARCH + ".dylib");
        case WINDOWS -> Set.of("skiko-windows-" + HOST_ARCH + ".dll", "icudtl.dat");
        case LINUX -> Set.of("libskiko-linux-" + HOST_ARCH + ".so");
      };
    }
  }
}
