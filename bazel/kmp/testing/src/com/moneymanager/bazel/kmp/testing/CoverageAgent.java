package com.moneymanager.bazel.kmp.testing;

import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * Starts Kover's JVM agent (the IntelliJ coverage engine) in a JVM test, under {@code --config=coverage}.
 *
 * <p>Kover's agent reads its settings from a file, which can't name a path per test, so this writes one that sends
 * the binary report to {@code $TEST_UNDECLARED_OUTPUTS_DIR/coverage.ic}, where Bazel keeps it with the test's results
 * (and caches it with them). The agent's argument is the Kover agent jar. Its manifest puts the jar on the boot class
 * path by its Maven file name, which a runfiles path doesn't keep, so this adds it there itself: the instrumented
 * classes call into the agent's runtime from every class loader.
 */
public final class CoverageAgent {
  private CoverageAgent() {}

  public static void premain(String koverAgentJar, Instrumentation instrumentation) throws Exception {
    Path settings = Files.createTempFile(Path.of(System.getenv("TEST_TMPDIR")), "kover", ".args");
    Files.writeString(
        settings,
        "report.file=" + Path.of(System.getenv("TEST_UNDECLARED_OUTPUTS_DIR"), "coverage.ic") + "\n"
            // Test classes share their module's packages; the report keeps only the production jars' classes.
            + "include=com.moneymanager.*\n");
    instrumentation.appendToBootstrapClassLoaderSearch(new JarFile(koverAgentJar));
    Class.forName("kotlinx.kover.jvmagent.KoverJvmAgentPremain", true, null)
        .getMethod("premain", String.class, Instrumentation.class)
        .invoke(null, "file:" + settings, instrumentation);
  }
}
