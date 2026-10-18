package com.moneymanager.bazel.kmp.android;

import com.android.build.gradle.ProguardFiles;
import com.android.build.gradle.internal.tasks.manifest.ManifestHelperKt;
import com.android.build.gradle.internal.tasks.manifest.ManifestProviderImpl;
import com.android.builder.dexing.KeepRuleFile;
import com.android.builder.dexing.MainDexListConfig;
import com.android.builder.dexing.ProguardConfig;
import com.android.builder.dexing.ProguardOutputFiles;
import com.android.builder.dexing.R8OutputType;
import com.android.builder.dexing.R8Tool;
import com.android.builder.dexing.ResourceShrinkingConfig;
import com.android.builder.dexing.ToolConfig;
import com.android.builder.internal.InstrumentedTestManifestGenerator;
import com.android.builder.internal.packaging.ApkFlinger;
import com.android.builder.packaging.DexFileComparator;
import com.android.builder.packaging.DexPackagingMode;
import com.android.builder.packaging.PackagingUtils;
import com.android.builder.symbols.BytecodeRClassWriterKt;
import com.android.ide.common.blame.Message;
import com.android.ide.common.blame.MessageReceiver;
import com.android.ide.common.signing.KeystoreHelper;
import com.android.ide.common.symbols.SymbolIo;
import com.android.ide.common.symbols.SymbolTable;
import com.android.manifmerger.ManifestMerger2;
import com.android.manifmerger.ManifestProvider;
import com.android.manifmerger.ManifestSystemProperty;
import com.android.manifmerger.MergingReport;
import com.android.manifmerger.PlaceholderHandler;
import com.android.tools.build.apkzlib.sign.SigningOptions;
import com.android.tools.build.apkzlib.zfile.ApkCreatorFactory;
import com.android.tools.build.apkzlib.zfile.NativeLibrariesPackagingMode;
import com.android.tools.profgen.ArtProfile;
import com.android.tools.profgen.ArtProfileKt;
import com.android.tools.profgen.ArtProfileSerializer;
import com.android.tools.profgen.DexFile;
import com.android.tools.profgen.DexFileParserKt;
import com.android.tools.profgen.Diagnostics;
import com.android.tools.profgen.HumanReadableProfile;
import com.android.tools.profgen.HumanReadableProfileKt;
import com.android.tools.profgen.ObfuscationMap;
import com.android.tools.profgen.ProfgenUtilsKt;
import com.android.utils.StdLogger;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * The steps of AGP's APK build that aren't a tool of their own (aapt2, D8, R8), done with AGP's own classes:
 * {@code manifest} is {@code processDebugManifest}, {@code test-manifest} a test APK's
 * {@code processAndroidDeviceTestManifest}, {@code rjar} the R.jar {@code processDebugResources} writes,
 * {@code proguard-defaults} {@code getDefaultProguardFile}, {@code embedded-rules} the keep rules AGP extracts from
 * library jars, {@code debug-keystore} the key AGP makes when ~/.android has none, and {@code package}
 * {@code packageDebug}: the APK through AGP's {@code ApkFlinger}, with AGP's
 * default Java-resource excludes and merges, its no-compress rules and 16 KB page alignment, v2-signed. A release
 * build adds {@code java-res}, {@code art-profile}, {@code r8}, {@code compile-art-profile} and {@code vcs-info}:
 * the release tasks of the same names.
 */
public final class AndroidApp {
  private AndroidApp() {}

  public static void main(String[] args) throws Exception {
    Map<String, List<String>> flags = parse(Arrays.copyOfRange(args, 1, args.length));
    switch (args[0]) {
      case "manifest" -> manifest(flags);
      case "test-manifest" -> testManifest(flags);
      case "rjar" -> rJar(flags);
      case "proguard-defaults" -> ProguardFiles.createProguardFile(one(flags, "name"), new File(one(flags, "out")));
      case "embedded-rules" -> embeddedRules(flags);
      case "java-res" -> javaRes(flags);
      case "art-profile" -> artProfile(flags);
      case "r8" -> r8(flags);
      case "compile-art-profile" -> compileArtProfile(flags);
      case "vcs-info" -> vcsInfo(flags);
      case "package" -> packageApk(flags);
      case "debug-keystore" -> KeystoreHelper.createDebugStore(
          null, new File(one(flags, "out")), "android", "android", "androiddebugkey",
          new StdLogger(StdLogger.Level.WARNING));
      default -> throw new IllegalArgumentException("unknown command " + args[0]);
    }
  }

  /** {@code --flag value} pairs; a repeated flag collects its values in order. */
  private static Map<String, List<String>> parse(String[] args) {
    Map<String, List<String>> out = new LinkedHashMap<>();
    for (int i = 0; i < args.length; i++) {
      if (!args[i].startsWith("--")) {
        throw new IllegalArgumentException("expected a flag, got " + args[i]);
      }
      String name = args[i].substring(2);
      boolean bool = i + 1 == args.length || args[i + 1].startsWith("--");
      out.computeIfAbsent(name, k -> new ArrayList<>()).add(bool ? "true" : args[++i]);
    }
    return out;
  }

  private static String one(Map<String, List<String>> flags, String name) {
    List<String> values = flags.get(name);
    if (values == null || values.size() != 1) {
      throw new IllegalArgumentException("expected one --" + name);
    }
    return values.get(0);
  }

  /** An app whose libraries ship no baseline profile has none to rewrite. */
  private static Path optionalPath(Map<String, List<String>> flags, String name) {
    return flags.containsKey(name) ? Path.of(one(flags, name)) : null;
  }

  private static List<String> all(Map<String, List<String>> flags, String name) {
    return flags.getOrDefault(name, List.of());
  }

  /** ProcessApplicationManifest: the app's manifest merged with its libraries', as AGP passes them. */
  private static void manifest(Map<String, List<String>> flags) throws IOException {
    List<ManifestProvider> deps = new ArrayList<>();
    for (String manifest : all(flags, "dep")) {
      deps.add(new ManifestProviderImpl(new File(manifest), manifest));
    }
    boolean debuggable = flags.containsKey("debuggable");
    List<ManifestMerger2.Invoker.Feature> features = new ArrayList<>();
    if (debuggable) {
      features.add(ManifestMerger2.Invoker.Feature.DEBUGGABLE);
    }
    features.add(ManifestMerger2.Invoker.Feature.VALIDATE_EXTRACT_NATIVE_LIBS_FROM_DEPENDENCIES);
    String applicationId = one(flags, "application-id");
    String out = one(flags, "out");
    MergingReport report = ManifestHelperKt.mergeManifests(
        new File(one(flags, "main")),
        List.of(),
        deps,
        List.of(),
        null,
        applicationId,
        one(flags, "namespace"),
        false,
        Integer.valueOf(one(flags, "version-code")),
        one(flags, "version-name"),
        one(flags, "min-sdk"),
        one(flags, "target-sdk"),
        null,
        false,
        // useLegacyPackaging unset and minSdk 23 or newer: AGP keeps native libraries uncompressed in the APK.
        Boolean.FALSE,
        out,
        null,
        ManifestMerger2.MergeType.APPLICATION,
        // manifestPlaceholders: the app sets none (mergeManifests adds applicationId itself).
        Map.of(),
        features,
        List.of(),
        null,
        null,
        new StdLogger(StdLogger.Level.WARNING),
        true,
        false,
        Integer.valueOf(one(flags, "compile-sdk")),
        false);
    if (report.getResult().isError()) {
      report.getLoggingRecords().forEach(r -> System.err.println(r));
      throw new IllegalStateException("manifest merge failed");
    }
  }

  /**
   * ProcessTestManifest for a library's self-instrumenting test APK: the instrumentation AGP generates, merged
   * under the test source set's manifest (when it has one), then with the libraries'.
   */
  private static void testManifest(Map<String, List<String>> flags) throws Exception {
    String applicationId = one(flags, "application-id");
    String minSdk = one(flags, "min-sdk");
    String targetSdk = one(flags, "target-sdk");
    String runner = one(flags, "runner");
    StdLogger logger = new StdLogger(StdLogger.Level.WARNING);
    Path tmp = Files.createTempDirectory("test-manifest");
    File generated = tmp.resolve("generated.xml").toFile();
    new InstrumentedTestManifestGenerator(
        generated, applicationId, minSdk, targetSdk, applicationId, runner, false, false).generate();
    if (flags.containsKey("test-manifest")) {
      ManifestMerger2.Invoker intermediate = ManifestMerger2.newMerger(
              new File(one(flags, "test-manifest")), logger, ManifestMerger2.MergeType.APPLICATION)
          .addLibraryManifest(generated)
          .addAllowedNonUniqueNamespace(applicationId)
          .setOverride(ManifestSystemProperty.Document.PACKAGE, applicationId)
          .setOverride(ManifestSystemProperty.UsesSdk.MIN_SDK_VERSION, minSdk)
          .setOverride(ManifestSystemProperty.Instrumentation.TARGET_PACKAGE, applicationId)
          .setNamespace(applicationId)
          .withFeatures(
              ManifestMerger2.Invoker.Feature.DISABLE_MINSDKLIBRARY_CHECK,
              ManifestMerger2.Invoker.Feature.CHECK_IF_PACKAGE_IN_MAIN_MANIFEST,
              ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING,
              ManifestMerger2.Invoker.Feature.DISABLE_REPLACE_WARNING);
      intermediate.setPlaceHolderValue(PlaceholderHandler.INSTRUMENTATION_RUNNER, runner);
      intermediate.setOverride(ManifestSystemProperty.Instrumentation.NAME, runner);
      intermediate.setOverride(ManifestSystemProperty.Instrumentation.FUNCTIONAL_TEST, "false");
      intermediate.setOverride(ManifestSystemProperty.Instrumentation.HANDLE_PROFILING, "false");
      intermediate.setOverride(ManifestSystemProperty.UsesSdk.TARGET_SDK_VERSION, targetSdk);
      File merged = tmp.resolve("intermediate.xml").toFile();
      writeMerged(intermediate.merge(), merged);
      generated = merged;
    }
    List<ManifestProvider> deps = new ArrayList<>();
    for (String manifest : all(flags, "dep")) {
      deps.add(new ManifestProviderImpl(new File(manifest), manifest));
    }
    List<ManifestMerger2.Invoker.Feature> features = new ArrayList<>(List.of(
        ManifestMerger2.Invoker.Feature.DISABLE_MINSDKLIBRARY_CHECK,
        ManifestMerger2.Invoker.Feature.REMOVE_TOOLS_DECLARATIONS,
        ManifestMerger2.Invoker.Feature.USES_SDK_IN_MANIFEST_LENIENT_HANDLING));
    // A library's test APK is debug; a com.android.test module's release variant isn't.
    if (flags.containsKey("debuggable")) {
      features.add(ManifestMerger2.Invoker.Feature.DEBUGGABLE);
    }
    ManifestMerger2.Invoker last = ManifestMerger2.newMerger(generated, logger, ManifestMerger2.MergeType.APPLICATION)
        .withFeatures(features.toArray(new ManifestMerger2.Invoker.Feature[0]))
        .setOverride(ManifestSystemProperty.Document.PACKAGE, applicationId)
        .addManifestProviders(deps)
        .setNamespace(applicationId);
    // As for the app: minSdk 23 or newer without useLegacyPackaging keeps native libraries uncompressed.
    last.setOverride(ManifestSystemProperty.Application.EXTRACT_NATIVE_LIBS, "false");
    writeMerged(last.merge(), new File(one(flags, "out")));
  }

  private static void writeMerged(MergingReport report, File out) throws IOException {
    if (report.getResult().isError()) {
      report.getLoggingRecords().forEach(r -> System.err.println(r));
      throw new IllegalStateException("manifest merge failed");
    }
    Files.writeString(out.toPath(), report.getMergedDocument(MergingReport.MergedManifestKind.MERGED));
  }

  /**
   * The app's R.jar: with non-transitive R classes each library's R class holds the symbols its own R.txt lists,
   * with the app's final ids from aapt2's R.txt. {@code --lib} is {@code <AndroidManifest.xml>:<R.txt>}, the R.txt
   * empty when the AAR has none.
   */
  private static void rJar(Map<String, List<String>> flags) throws Exception {
    SymbolTable app = SymbolIo.readFromAapt(new File(one(flags, "r-txt")), one(flags, "namespace"));
    List<SymbolTable> tables = new ArrayList<>();
    tables.add(SymbolTable.builder().tablePackage(one(flags, "namespace")).build());
    for (String lib : all(flags, "lib")) {
      String[] parts = lib.split(":", 2);
      String pkg = packageOf(Path.of(parts[0]));
      File rTxt = new File(parts[1]);
      SymbolTable own = rTxt.length() == 0
          ? SymbolTable.builder().tablePackage(pkg).build()
          : SymbolIo.readFromAaptNoValues(rTxt, pkg);
      tables.add(app.filter(own).rename(pkg));
    }
    // Non-final, as android.nonFinalResIds (on by default) makes them: optimized resource shrinking traces them.
    BytecodeRClassWriterKt.exportToCompiledJava(tables, Path.of(one(flags, "out")), false, null, null);
  }

  private static String packageOf(Path manifest) throws Exception {
    return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest.toFile())
        .getDocumentElement().getAttribute("package");
  }

  /**
   * The keep rules library jars carry, as AGP extracts them for R8: a jar's {@code META-INF/com.android.tools/r8*}
   * rules when it has any (the directory whose version range takes R8's), else its {@code META-INF/proguard} ones,
   * after an AAR's own {@code proguard.txt} ({@code --aar-rules <jar>=<proguard.txt>}): one library at a time, in
   * classpath order, as AGP hands R8 the runtime classpath's rules.
   */
  private static void embeddedRules(Map<String, List<String>> flags) throws IOException {
    Map<String, String> aarRules = new LinkedHashMap<>();
    for (String rule : all(flags, "aar-rules")) {
      String[] parts = rule.split("=", 2);
      aarRules.put(parts[0], parts[1]);
    }
    StringBuilder out = new StringBuilder();
    for (String jar : all(flags, "jar")) {
      if (aarRules.containsKey(jar)) {
        out.append("# ").append(aarRules.get(jar)).append('\n');
        out.append(Files.readString(Path.of(aarRules.get(jar)))).append('\n');
      }
      try (ZipFile zip = new ZipFile(jar)) {
        List<ZipEntry> r8 = new ArrayList<>();
        List<ZipEntry> proguard = new ArrayList<>();
        for (ZipEntry e : zip.stream().sorted((a, b) -> a.getName().compareTo(b.getName())).toList()) {
          String name = e.getName();
          if (e.isDirectory()) {
            continue;
          }
          if (name.startsWith("META-INF/com.android.tools/") && r8Applies(name)) {
            r8.add(e);
          } else if (name.startsWith("META-INF/proguard/")) {
            proguard.add(e);
          }
        }
        boolean hasR8Dir = zip.stream().anyMatch(e -> e.getName().startsWith("META-INF/com.android.tools/r8"));
        for (ZipEntry e : hasR8Dir ? r8 : proguard) {
          out.append("# ").append(e.getName()).append('\n');
          try (InputStream in = zip.getInputStream(e)) {
            out.append(new String(in.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
          }
        }
      }
    }
    Files.writeString(Path.of(one(flags, "out")), out);
  }

  private static final Pattern R8_DIR = Pattern.compile(
      "META-INF/com\\.android\\.tools/r8(?:-from-([0-9.]+))?(?:-upto-([0-9.]+))?/[^/]+");

  /** Whether a {@code r8[-from-<v>][-upto-<v>]} directory's range takes the R8 that AGP bundles. */
  private static boolean r8Applies(String name) {
    var m = R8_DIR.matcher(name);
    if (!m.matches()) {
      return false;
    }
    String version = com.android.tools.r8.Version.getVersionString().split(" ")[0];
    return (m.group(1) == null || compare(version, m.group(1)) >= 0)
        && (m.group(2) == null || compare(version, m.group(2)) < 0);
  }

  private static int compare(String a, String b) {
    String[] x = a.split("[.-]");
    String[] y = b.split("[.-]");
    for (int i = 0; i < Math.max(x.length, y.length); i++) {
      int p = i < x.length ? number(x[i]) : 0;
      int q = i < y.length ? number(y[i]) : 0;
      if (p != q) {
        return Integer.compare(p, q);
      }
    }
    return 0;
  }

  private static int number(String part) {
    return part.chars().allMatch(Character::isDigit) && !part.isEmpty() ? Integer.parseInt(part) : 0;
  }

  /** PackagingOptionsUtils' defaultExcludes, plus the kotlin_module files an APK drops (ResourcesApkPackagingImpl). */
  private static final List<Pattern> EXCLUDES = Stream.of(
          "/META-INF/LICENSE", "/META-INF/LICENSE.txt", "/META-INF/MANIFEST.MF", "/META-INF/**/MANIFEST.MF",
          "/META-INF/NOTICE", "/META-INF/NOTICE.txt", "/META-INF/*.DSA", "/META-INF/*.EC", "/META-INF/*.SF",
          "/META-INF/*.RSA", "/META-INF/maven/**", "/META-INF/proguard/*", "/META-INF/com.android.tools/**",
          "/NOTICE", "/NOTICE.txt", "/LICENSE.txt", "/LICENSE", "**/.svn/**", "**/CVS/**", "**/SCCS/**", "**/.*/**",
          "**/.*", "**/*~", "**/thumbs.db", "**/picasa.ini", "**/about.html", "**/package.html", "**/overview.html",
          "**/protobuf.meta", "**/_*", "**/_*/**", "**/*.kotlin_metadata", "/META-INF/*.kotlin_module")
      .map(AndroidApp::glob).toList();

  /** defaultMerges. */
  private static final List<Pattern> MERGES =
      Stream.of("/META-INF/services/**", "jacoco-agent.properties").map(AndroidApp::glob).toList();

  /** AGP's glob: {@code **}{@code /} is any number of directories (none too), {@code *} stays in one. */
  private static Pattern glob(String glob) {
    StringBuilder re = new StringBuilder(glob.startsWith("/") ? "" : "(?:.*/)?");
    String g = glob.startsWith("/") ? glob.substring(1) : glob;
    for (int i = 0; i < g.length(); i++) {
      char c = g.charAt(i);
      if (g.startsWith("**/", i)) {
        re.append("(?:.*/)?");
        i += 2;
      } else if (g.startsWith("**", i)) {
        re.append(".*");
        i++;
      } else if (c == '*') {
        re.append("[^/]*");
      } else if (c == '?') {
        re.append("[^/]");
      } else {
        re.append(Pattern.quote(String.valueOf(c)));
      }
    }
    return Pattern.compile(re.toString());
  }

  private static boolean matches(List<Pattern> patterns, String path) {
    return patterns.stream().anyMatch(p -> p.matcher(path).matches());
  }

  /**
   * The APK: aapt2's linked resources and manifest, the dex files, the Java resources of the runtime jars (in
   * classpath order: excluded, merged or the first one kept), the AARs' assets and native libraries, any
   * {@code --file <path in the APK>=<file>} (the ART profile, the VCS info), and AGP's app metadata.
   */
  private static void packageApk(Map<String, List<String>> flags) throws Exception {
    Map<String, byte[]> javaRes = mergeJavaRes(all(flags, "java-res"));
    Map<String, File> files = new LinkedHashMap<>();
    for (String root : all(flags, "aar-root")) {
      collect(Path.of(root, "assets"), "assets/", files);
      Path jni = Path.of(root, "jni");
      if (Files.isDirectory(jni)) {
        collect(jni, "lib/", files);
      }
    }
    for (String file : all(flags, "file")) {
      String[] parts = file.split("=", 2);
      files.put(parts[0], new File(parts[1]));
    }
    int minSdk = Integer.parseInt(one(flags, "min-sdk"));
    // useLegacyPackaging unset: AGP's default is legacy (compressed) below minSdk 23 for native libraries and 28 for dex.
    NativeLibrariesPackagingMode jniMode = PackagingUtils.getNativeLibrariesLibrariesPackagingMode(minSdk < 23);
    DexPackagingMode dexMode = PackagingUtils.getDexPackagingMode(null, minSdk < 28);
    Predicate<String> noCompress = PackagingUtils.getNoCompressPredicate(List.of(), jniMode, dexMode);
    File out = new File(one(flags, "out"));
    ApkCreatorFactory.CreationData.Builder data = ApkCreatorFactory.CreationData.builder()
        .setApkPath(out)
        .setNativeLibrariesPackagingMode(jniMode)
        .setNoCompressPredicate(noCompress::test)
        .setIncremental(false);
    if (flags.containsKey("keystore")) {
      KeyStore store = KeyStore.getInstance(new File(one(flags, "keystore")), one(flags, "store-password").toCharArray());
      String alias = one(flags, "key-alias");
      data.setSigningOptions(SigningOptions.builder()
          .setKey((PrivateKey) store.getKey(alias, one(flags, "key-password").toCharArray()))
          .setCertificates((X509Certificate) store.getCertificate(alias))
          .setV1SigningEnabled(false)
          .setV2SigningEnabled(true)
          .setMinSdkVersion(minSdk)
          .setValidation(SigningOptions.Validation.ASSUME_INVALID)
          .build());
    }
    Path scratch = Files.createTempDirectory("apk");
    try (ApkFlinger apk = new ApkFlinger(
        data.build(), Deflater.DEFAULT_COMPRESSION, true, false, false, ApkFlinger.PAGE_SIZE_16K)) {
      apk.writeZip(new File(one(flags, "resources")), null, null);
      List<String> dexes = new ArrayList<>();
      for (String dex : all(flags, "dex")) {
        try (ZipFile zip = new ZipFile(dex)) {
          for (ZipEntry e : zip.stream().filter(e -> e.getName().matches("classes\\d*\\.dex")).toList()) {
            Path file = scratch.resolve("dex" + dexes.size());
            try (InputStream in = zip.getInputStream(e)) {
              Files.copy(in, file);
            }
            dexes.add(file.toString());
          }
        }
      }
      for (int i = 0; i < dexes.size(); i++) {
        apk.writeFile(new File(dexes.get(i)), i == 0 ? "classes.dex" : "classes" + (i + 1) + ".dex");
      }
      for (Map.Entry<String, File> f : files.entrySet()) {
        apk.writeFile(f.getValue(), f.getKey());
      }
      // AGP's merged Java resources are an apkzlib jar, whose compressor stores an entry deflating doesn't shrink,
      // and writeZip keeps each entry's compression.
      Path resJar = scratch.resolve("java-res.jar");
      try (ZipOutputStream jar = new ZipOutputStream(Files.newOutputStream(resJar))) {
        for (Map.Entry<String, byte[]> res : javaRes.entrySet()) {
          ZipEntry e = new ZipEntry(res.getKey());
          byte[] bytes = res.getValue();
          if (deflatedSize(bytes) >= bytes.length) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            e.setMethod(ZipEntry.STORED);
            e.setSize(bytes.length);
            e.setCrc(crc.getValue());
          }
          jar.putNextEntry(e);
          jar.write(bytes);
          jar.closeEntry();
        }
      }
      apk.writeZip(resJar.toFile(), null, null);
      if (flags.containsKey("agp-version")) {
        Path metadata = scratch.resolve("app-metadata.properties");
        Files.writeString(metadata, "appMetadataVersion=1.1\nandroidGradlePluginVersion=" + one(flags, "agp-version") + "\n");
        apk.writeFile(metadata.toFile(), "META-INF/com/android/build/gradle/app-metadata.properties");
      }
    }
  }

  /**
   * MergeJavaResourceTask: the Java resources of the runtime jars, in classpath order, excluded, merged or the first
   * one kept, as one jar (R8's input in a release build).
   */
  private static void javaRes(Map<String, List<String>> flags) throws IOException {
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(Path.of(one(flags, "out"))))) {
      for (Map.Entry<String, byte[]> res : mergeJavaRes(all(flags, "jar")).entrySet()) {
        ZipEntry e = new ZipEntry(res.getKey());
        e.setTimeLocal(ZIP_EPOCH);
        out.putNextEntry(e);
        out.write(res.getValue());
        out.closeEntry();
      }
    }
  }

  /** The DOS epoch zip tools use for reproducible entries. */
  private static final LocalDateTime ZIP_EPOCH = LocalDateTime.of(1980, 2, 1, 0, 0);

  /**
   * mergeReleaseArtProfile and expandReleaseArtProfileWildcards: the libraries' baseline-prof.txt joined (the app
   * has none of its own), then every wildcard rule expanded against the program's classes, as R8 takes it.
   */
  private static void artProfile(Map<String, List<String>> flags) throws IOException {
    List<String> parts = new ArrayList<>();
    for (String profile : all(flags, "profile")) {
      parts.add(Files.readString(Path.of(profile)));
    }
    Path merged = Path.of(one(flags, "out") + ".merged");
    Files.writeString(merged, String.join("\n", parts));
    Diagnostics diagnostics = error -> {
      throw new IllegalStateException("Error parsing baseline-prof.txt : " + error);
    };
    ProfgenUtilsKt.expandWildcards(merged.toString(), one(flags, "out"), all(flags, "jar"), diagnostics);
    Files.delete(merged);
  }

  /**
   * minifyReleaseWithR8, as R8Task runs it: AGP's {@code runR8} with its defaults (full mode, strict full-mode keep
   * rules, no main-dex list), the merged Java resources, the ART profile R8 rewrites, and optimized resource
   * shrinking over aapt2's proto-format link (the AARs' raw resources give the {@code tools:keep} rules).
   */
  private static void r8(Map<String, List<String>> flags) throws Exception {
    Path scratch = Files.createTempDirectory("r8");
    List<KeepRuleFile> rules = new ArrayList<>();
    for (String rule : all(flags, "pg-conf")) {
      rules.add(new KeepRuleFile.WithoutOrigin(rule));
    }
    ProguardOutputFiles outputs = new ProguardOutputFiles(
        Path.of(one(flags, "mapping")),
        scratch.resolve("mapping.prt"),
        scratch.resolve("seeds.txt"),
        scratch.resolve("usage.txt"),
        Path.of(one(flags, "configuration")),
        scratch.resolve("missing_rules.txt"));
    ProguardConfig proguard = new ProguardConfig(rules, null, List.of(), outputs, null);
    int minSdk = Integer.parseInt(one(flags, "min-sdk"));
    ToolConfig tool = new ToolConfig(minSdk, false, false, false, false, true, true, null, R8OutputType.DEX, true);
    List<File> rawRes = all(flags, "raw-res").stream().map(File::new).toList();
    ResourceShrinkingConfig shrinking = new ResourceShrinkingConfig(
        List.of(new File(one(flags, "resources"))),
        rawRes,
        List.of(),
        true,
        true,
        null,
        new ResourceShrinkingConfig.ShrinkOutput(List.of(new File(one(flags, "shrunk-resources"))), null));
    Path dexDir = scratch.resolve("dex");
    Files.createDirectories(dexDir);
    // AGP logs R8's info messages (rules that match nothing) at Gradle's info level, which a build doesn't show.
    MessageReceiver messages = message -> {
      if (message.getKind() != Message.Kind.INFO) {
        System.err.println(message.getKind() + ": " + message.getText());
      }
    };
    ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
    try {
      R8Tool.runR8(
          all(flags, "program").stream().map(Path::of).toList(),
          dexDir,
          Path.of(one(flags, "java-res")),
          Path.of(one(flags, "java-res-out")),
          List.of(Path.of(one(flags, "lib"))),
          List.of(),
          tool,
          proguard,
          new MainDexListConfig(),
          shrinking,
          messages,
          List.of(),
          List.of(),
          null,
          null,
          null,
          optionalPath(flags, "art-profile"),
          optionalPath(flags, "art-profile-out"),
          null,
          null,
          null,
          pool);
    } finally {
      pool.shutdown();
    }
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(Path.of(one(flags, "dex"))));
        Stream<Path> dexes = Files.list(dexDir)) {
      for (Path dex : dexes.sorted().toList()) {
        ZipEntry e = new ZipEntry(dex.getFileName().toString());
        e.setTimeLocal(ZIP_EPOCH);
        out.putNextEntry(e);
        Files.copy(dex, out);
        out.closeEntry();
      }
    }
  }

  /**
   * compileReleaseArtProfile: R8's rewritten profile against the dex files (in the order they're packaged, no
   * obfuscation map: R8 already renamed the rules), saved for ART (P's format) and as its metadata.
   */
  private static void compileArtProfile(Map<String, List<String>> flags) throws Exception {
    Diagnostics diagnostics = error -> {
      throw new IllegalStateException("Error parsing baseline-prof.txt : " + error);
    };
    HumanReadableProfile profile = HumanReadableProfileKt.HumanReadableProfile(new File(one(flags, "profile")), diagnostics);
    if (profile == null) {
      throw new IllegalStateException("Merged baseline-prof.txt cannot be parsed successfully.");
    }
    Path scratch = Files.createTempDirectory("dex");
    List<File> dexFiles = new ArrayList<>();
    try (ZipFile zip = new ZipFile(one(flags, "dex"))) {
      for (ZipEntry e : zip.stream().toList()) {
        Path file = scratch.resolve(e.getName());
        try (InputStream in = zip.getInputStream(e)) {
          Files.copy(in, file);
        }
        dexFiles.add(file.toFile());
      }
    }
    dexFiles.sort(DexFileComparator.INSTANCE);
    List<DexFile> dex = new ArrayList<>();
    for (File file : dexFiles) {
      try (InputStream in = new BufferedInputStream(Files.newInputStream(file.toPath()))) {
        dex.addAll(DexFileParserKt.parseDexFiles(in, dex.size()));
      }
    }
    ArtProfile art = ArtProfileKt.ArtProfile(profile, ObfuscationMap.Companion.getEmpty(), dex, "");
    try (OutputStream out = Files.newOutputStream(Path.of(one(flags, "out")))) {
      art.save(out, ArtProfileSerializer.V0_1_0_P);
    }
    try (OutputStream out = Files.newOutputStream(Path.of(one(flags, "out-metadata")))) {
      art.save(out, ArtProfileSerializer.METADATA_0_0_2);
    }
  }

  /**
   * The VCS info AGP records in a release APK ({@code vcsInfo.include}): the commit from the workspace status's
   * {@code STABLE_GIT_COMMIT}, so only a stamped build ({@code --stamp}) has it.
   */
  private static void vcsInfo(Map<String, List<String>> flags) throws IOException {
    String revision = null;
    for (String line : Files.readAllLines(Path.of(one(flags, "status")))) {
      if (line.startsWith("STABLE_GIT_COMMIT ")) {
        revision = line.substring("STABLE_GIT_COMMIT ".length()).trim();
      }
    }
    if (revision == null) {
      throw new IllegalStateException("--stamp without STABLE_GIT_COMMIT: the workspace status command didn't run");
    }
    Files.writeString(Path.of(one(flags, "out")),
        "repositories {\n  system: GIT\n  local_root_path: \"$PROJECT_DIR\"\n  revision: \"" + revision + "\"\n}\n");
  }

  private static int deflatedSize(byte[] bytes) {
    Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
    deflater.setInput(bytes);
    deflater.finish();
    byte[] buffer = new byte[8192];
    int size = 0;
    while (!deflater.finished()) {
      size += deflater.deflate(buffer);
    }
    deflater.end();
    return size;
  }

  private static Map<String, byte[]> mergeJavaRes(List<String> jars) throws IOException {
    Map<String, byte[]> javaRes = new LinkedHashMap<>();
    Map<String, String> owners = new LinkedHashMap<>();
    for (String jar : jars) {
      try (ZipInputStream in = new ZipInputStream(Files.newInputStream(Path.of(jar)))) {
        for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
          String path = e.getName();
          if (e.isDirectory() || path.endsWith(".dex") || !PackagingUtils.checkFileForApkPackaging(path, false)
              || matches(EXCLUDES, path)) {
            continue;
          }
          byte[] bytes = in.readAllBytes();
          if (!javaRes.containsKey(path)) {
            javaRes.put(path, bytes);
            owners.put(path, jar);
          } else if (matches(MERGES, path)) {
            javaRes.put(path, merge(javaRes.get(path), bytes));
          } else if (!Arrays.equals(javaRes.get(path), bytes)) {
            throw new IllegalStateException(
                "2 files found with path '" + path + "' from inputs " + owners.get(path) + " and " + jar);
          }
        }
      }
    }
    return javaRes;
  }

  /** A merged file's parts, each ending in a newline, as AGP's MergedFile joins service files. */
  private static byte[] merge(byte[] first, byte[] second) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(first);
    if (first.length > 0 && first[first.length - 1] != '\n') {
      out.write('\n');
    }
    out.write(second);
    return out.toByteArray();
  }

  private static void collect(Path dir, String prefix, Map<String, File> into) throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(dir)) {
      for (Path p : walk.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
        String path = prefix + dir.relativize(p).toString().replace(File.separatorChar, '/');
        into.putIfAbsent(path, p.toFile());
      }
    }
  }
}
