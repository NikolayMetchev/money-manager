package com.moneymanager.bazel.kmp.testing;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.Test;
import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Request;
import org.junit.runner.Result;
import org.junit.runner.manipulation.Filter;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * Runs every JUnit 4 test class in one test jar, for {@code kmp_test}.
 *
 * <p>Bazel's own runner needs a single {@code test_class}, and KMP source layouts don't give us one. The jar
 * is named by {@code -Dkmp.test.jar} and found on the classpath. {@code kotlin.test.Test} is a typealias for
 * {@link Test} under kotlin-test-junit, so a class is a test class when it is concrete and has a method
 * annotated with it. Results go to {@code $XML_OUTPUT_FILE} as JUnit XML, and {@code --test_filter}
 * ({@code TESTBRIDGE_TEST_ONLY}) is a substring of {@code Class#method}.
 */
public final class KmpJUnitRunner {
  private KmpJUnitRunner() {}

  public static void main(String[] args) throws Exception {
    File jar = findTestJar(System.getProperty("kmp.test.jar"));
    List<Class<?>> classes = testClasses(jar);
    if (classes.isEmpty()) {
      System.err.println("No test classes in " + jar);
      System.exit(1);
    }

    Request request = Request.classes(classes.toArray(new Class<?>[0]));
    String only = System.getenv("TESTBRIDGE_TEST_ONLY");
    if (only != null && !only.isEmpty()) {
      request = request.filterWith(new SubstringFilter(only));
    }

    JUnitCore core = new JUnitCore();
    XmlListener xml = new XmlListener();
    core.addListener(xml);
    core.addListener(new ConsoleListener());
    Result result = core.run(request);

    String xmlOutput = System.getenv("XML_OUTPUT_FILE");
    if (xmlOutput != null) {
      xml.write(Path.of(xmlOutput));
    }
    System.err.printf(
        "%d tests, %d failed, %d ignored%n",
        result.getRunCount(), result.getFailureCount(), result.getIgnoreCount());
    System.exit(result.wasSuccessful() && result.getRunCount() > 0 ? 0 : 1);
  }

  private static File findTestJar(String name) {
    if (name == null) {
      throw new IllegalStateException("-Dkmp.test.jar is not set");
    }
    for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
      File file = new File(entry);
      if (file.getName().equals(name)) {
        return file;
      }
    }
    throw new IllegalStateException(name + " is not on the classpath");
  }

  private static List<Class<?>> testClasses(File jar) throws IOException, ClassNotFoundException {
    List<Class<?>> classes = new ArrayList<>();
    ClassLoader loader = KmpJUnitRunner.class.getClassLoader();
    try (JarFile file = new JarFile(jar)) {
      Enumeration<JarEntry> entries = file.entries();
      while (entries.hasMoreElements()) {
        String entry = entries.nextElement().getName();
        if (!entry.endsWith(".class") || entry.startsWith("META-INF/")) {
          continue;
        }
        String className = entry.substring(0, entry.length() - ".class".length()).replace('/', '.');
        Class<?> type = Class.forName(className, false, loader);
        if (isTestClass(type)) {
          classes.add(type);
        }
      }
    }
    classes.sort((a, b) -> a.getName().compareTo(b.getName()));
    return classes;
  }

  private static boolean isTestClass(Class<?> type) {
    int modifiers = type.getModifiers();
    if (Modifier.isAbstract(modifiers) || !Modifier.isPublic(modifiers) || type.isSynthetic()) {
      return false;
    }
    // Nested test classes count, but JUnit 4 can only construct static ones.
    if (type.isAnonymousClass() || type.isLocalClass() || (type.isMemberClass() && !Modifier.isStatic(modifiers))) {
      return false;
    }
    for (Method method : type.getMethods()) {
      if (method.isAnnotationPresent(Test.class)) {
        return true;
      }
    }
    return false;
  }

  private static final class SubstringFilter extends Filter {
    private final String text;

    SubstringFilter(String text) {
      this.text = text;
    }

    @Override
    public boolean shouldRun(Description description) {
      if (description.getMethodName() == null) {
        for (Description child : description.getChildren()) {
          if (shouldRun(child)) {
            return true;
          }
        }
        return false;
      }
      return (description.getClassName() + "#" + description.getMethodName()).contains(text);
    }

    @Override
    public String describe() {
      return "contains " + text;
    }
  }

  private static final class ConsoleListener extends RunListener {
    @Override
    public void testFailure(Failure failure) {
      System.err.println("FAILED " + failure.getDescription().getDisplayName());
      System.err.println(failure.getTrace());
    }
  }

  /** Collects one testsuite element per class. */
  private static final class XmlListener extends RunListener {
    private static final class Case {
      final String name;
      long startNanos;
      double seconds;
      Failure failure;
      boolean skipped;

      Case(String name) {
        this.name = name;
      }
    }

    private final Map<String, Map<String, Case>> suites = new LinkedHashMap<>();

    private Case caseFor(Description description) {
      // A @BeforeClass/@AfterClass failure is reported on the class itself, with no method name. Gradle's
      // reports call that case "classMethod".
      String method = description.getMethodName() != null ? description.getMethodName() : "classMethod";
      return suites
          .computeIfAbsent(description.getClassName(), k -> new LinkedHashMap<>())
          .computeIfAbsent(method, Case::new);
    }

    @Override
    public void testStarted(Description description) {
      caseFor(description).startNanos = System.nanoTime();
    }

    @Override
    public void testFinished(Description description) {
      Case c = caseFor(description);
      c.seconds = (System.nanoTime() - c.startNanos) / 1e9;
    }

    @Override
    public void testFailure(Failure failure) {
      caseFor(failure.getDescription()).failure = failure;
    }

    @Override
    public void testAssumptionFailure(Failure failure) {
      caseFor(failure.getDescription()).skipped = true;
    }

    @Override
    public void testIgnored(Description description) {
      caseFor(description).skipped = true;
    }

    void write(Path path) throws IOException {
      StringWriter out = new StringWriter();
      out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
      for (Map.Entry<String, Map<String, Case>> suite : suites.entrySet()) {
        int failures = 0;
        int skipped = 0;
        double seconds = 0;
        for (Case c : suite.getValue().values()) {
          failures += c.failure != null ? 1 : 0;
          skipped += c.skipped ? 1 : 0;
          seconds += c.seconds;
        }
        out.write(
            String.format(
                "  <testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" errors=\"0\" skipped=\"%d\" time=\"%.3f\">\n",
                escape(suite.getKey()), suite.getValue().size(), failures, skipped, seconds));
        for (Case c : suite.getValue().values()) {
          out.write(
              String.format(
                  "    <testcase classname=\"%s\" name=\"%s\" time=\"%.3f\"",
                  escape(suite.getKey()), escape(c.name), c.seconds));
          if (c.failure != null) {
            out.write(">\n      <failure message=\"");
            out.write(escape(String.valueOf(c.failure.getMessage())));
            out.write("\">");
            out.write(escape(c.failure.getTrace()));
            out.write("</failure>\n    </testcase>\n");
          } else if (c.skipped) {
            out.write(">\n      <skipped/>\n    </testcase>\n");
          } else {
            out.write("/>\n");
          }
        }
        out.write("  </testsuite>\n");
      }
      out.write("</testsuites>\n");
      try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
        writer.write(out.toString());
      }
    }

    private static String escape(String text) {
      StringBuilder sb = new StringBuilder(text.length());
      for (char ch : text.toCharArray()) {
        switch (ch) {
          case '<' -> sb.append("&lt;");
          case '>' -> sb.append("&gt;");
          case '&' -> sb.append("&amp;");
          case '"' -> sb.append("&quot;");
          default -> {
            if (ch < 0x20 && ch != '\n' && ch != '\t' && ch != '\r') {
              sb.append(' ');
            } else {
              sb.append(ch);
            }
          }
        }
      }
      return sb.toString();
    }
  }
}
