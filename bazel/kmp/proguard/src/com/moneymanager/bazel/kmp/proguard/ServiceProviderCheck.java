package com.moneymanager.bazel.kmp.proguard;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Gradle's {@code verifyProguardServiceProviders}, as a test over {@code proguard_jars}' output: ProGuard doesn't
 * follow {@code META-INF/services}, so it silently strips classes only reached through ServiceLoader (the SQLite
 * JDBC driver, the crypto provider, …), and the release app then fails at runtime while the build passes.
 *
 * <p>The arguments are the shrunk jars. The report ({@code ok}/{@code MISSING} per provider) goes to the test's
 * undeclared outputs.
 */
public final class ServiceProviderCheck {
  private static final String SERVICES = "META-INF/services/";

  // Annotation processors are compile-time only; the app never loads them.
  private static final String PROCESSOR = "javax.annotation.processing.Processor";

  private ServiceProviderCheck() {}

  public static void main(String[] args) throws Exception {
    Set<String> classes = new HashSet<>();
    List<String[]> providers = new ArrayList<>();
    for (String arg : args) {
      try (ZipFile zip = new ZipFile(arg)) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
          ZipEntry entry = entries.nextElement();
          String name = entry.getName();
          if (name.endsWith(".class")) {
            classes.add(name.substring(0, name.length() - ".class".length()).replace('/', '.'));
          } else if (name.startsWith(SERVICES) && !entry.isDirectory()) {
            String service = name.substring(SERVICES.length());
            try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8))) {
              for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String provider = line.split("#", 2)[0].trim();
                if (!provider.isEmpty()) {
                  providers.add(new String[] {service, provider});
                }
              }
            }
          }
        }
      }
    }
    StringBuilder report = new StringBuilder();
    List<String> missing = new ArrayList<>();
    for (String[] p : providers) {
      boolean kept = classes.contains(p[1]);
      report.append(kept ? "ok" : "MISSING").append(' ').append(p[0]).append(" -> ").append(p[1]).append('\n');
      if (!kept && !p[0].equals(PROCESSOR)) {
        missing.add("  " + p[0] + " -> " + p[1]);
      }
    }
    String outputs = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR");
    if (outputs != null) {
      Files.writeString(Path.of(outputs, "service-providers.txt"), report);
    }
    if (!missing.isEmpty()) {
      System.err.println(
          "ProGuard removed ServiceLoader providers; add -keep rules to proguard-rules.pro:\n"
              + String.join("\n", missing));
      System.exit(1);
    }
    System.out.println(providers.size() + " service providers, all kept.");
  }
}
