package com.moneymanager.bazel.kmp.r8;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.JdkClassFileProvider;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.R8;
import com.android.tools.r8.R8Command;
import com.android.tools.r8.origin.PathOrigin;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Runs R8 for {@code r8_jars}, with class files, not DEX, as its output.
 *
 * <p>The arguments are the configuration file the rule writes and the output jar. {@code {compose_rules}} in the
 * configuration becomes the Compose Gradle plugin's own {@code default-compose-desktop-rules.pro}, read from the
 * plugin's jar on this classpath, so the rules move with the catalog's Compose version.
 *
 * <p>The configuration goes to R8 as text rather than as a file: R8 resolves a file's {@code -injars} against the
 * file's own directory, and the rule writes them relative to the execution root, the working directory.
 *
 * <p>The library classes are this runner's own JDK's: R8 reads a JDK home, not jmods. The runner's JDK is
 * MODULE.bazel's JDK, the one the app is compiled against and bundles.
 */
public final class R8Runner {
  private static final String COMPOSE_RULES = "default-compose-desktop-rules.pro";

  private R8Runner() {}

  static void main(String[] args) throws Exception {
    Path config = Path.of(args[0]);
    Path output = Path.of(args[1]);
    Path composeRules = Files.createTempFile("compose-rules", ".pro");
    try {
      try (InputStream in = R8Runner.class.getClassLoader().getResourceAsStream(COMPOSE_RULES)) {
        if (in == null) {
          throw new IllegalStateException(COMPOSE_RULES + " isn't on the classpath (compose-gradle-plugin)");
        }
        Files.copy(in, composeRules, StandardCopyOption.REPLACE_EXISTING);
      }
      String text = Files.readString(config).replace("{compose_rules}", composeRules.toString());
      R8Command command =
          R8Command.builder()
              .addProguardConfiguration(List.of(text), new PathOrigin(config))
              .addLibraryResourceProvider(JdkClassFileProvider.fromJdkHome(Path.of(System.getProperty("java.home"))))
              // Compatibility mode: R8's full mode also drops default constructors and attributes that no keep
              // rule names.
              .setProguardCompatibility(true)
              .setMode(CompilationMode.RELEASE)
              .setOutput(output, OutputMode.ClassFile)
              .build();
      R8.run(command);
    } finally {
      Files.deleteIfExists(composeRules);
    }
  }
}
