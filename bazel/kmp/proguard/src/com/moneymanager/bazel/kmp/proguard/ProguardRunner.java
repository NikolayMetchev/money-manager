package com.moneymanager.bazel.kmp.proguard;

import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.JarOutputStream;
import proguard.ClassPath;
import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.ProGuard;

/**
 * Runs ProGuard for {@code proguard_jars} the way Compose Desktop's {@code proguardReleaseJars} does.
 *
 * <p>The one argument is the configuration file the rule writes. {@code {compose_rules}} in it becomes the
 * Compose Gradle plugin's own {@code default-compose-desktop-rules.pro}, read from the plugin's jar on this
 * classpath, so the rules move with the catalog's Compose version.
 *
 * <p>Bazel declares every output jar up front, but ProGuard writes no jar for an input that shrinks to nothing;
 * those get an empty jar.
 */
public final class ProguardRunner {
  private static final String COMPOSE_RULES = "default-compose-desktop-rules.pro";

  private ProguardRunner() {}

  static void main(String[] args) throws Exception {
    Path composeRules = Files.createTempFile("compose-rules", ".pro");
    try {
      try (InputStream in = ProguardRunner.class.getClassLoader().getResourceAsStream(COMPOSE_RULES)) {
        if (in == null) {
          throw new IllegalStateException(COMPOSE_RULES + " isn't on the classpath (compose-gradle-plugin)");
        }
        Files.copy(in, composeRules, StandardCopyOption.REPLACE_EXISTING);
      }
      String text = Files.readString(Path.of(args[0])).replace("{compose_rules}", composeRules.toString());
      Configuration configuration = new Configuration();
      // Paths in the configuration are relative to the execution root, the working directory.
      try (ConfigurationParser parser =
          new ConfigurationParser(text, args[0], new File("."), System.getProperties())) {
        parser.parse(configuration);
      }
      new ProGuard(configuration).execute();
      writeMissingOutputs(configuration.programJars);
    } finally {
      Files.deleteIfExists(composeRules);
    }
  }

  private static void writeMissingOutputs(ClassPath programJars) throws Exception {
    for (int i = 0; i < programJars.size(); i++) {
      if (!programJars.get(i).isOutput()) {
        continue;
      }
      Path jar = programJars.get(i).getFile().toPath();
      if (!Files.exists(jar)) {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
          out.finish();
        }
      }
    }
  }
}
