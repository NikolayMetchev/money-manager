package com.moneymanager.bazel.kmp.testing;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
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

  // Public although Java 25 doesn't need it: under `bazel coverage` JaCoCo's runner finds main with Class.getMethod.
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
    classes.sort(Comparator.comparing(Class::getName));
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

  /** Records each case into the shared {@link JUnitXml} report. */
  private static final class XmlListener extends RunListener {
    private final JUnitXml report = new JUnitXml();
    private final Map<JUnitXml.Case, Long> startNanos = new HashMap<>();

    private JUnitXml.Case caseFor(Description description) {
      // A @BeforeClass/@AfterClass failure is reported on the class itself, with no method name. Gradle's
      // reports call that case "classMethod".
      String method = description.getMethodName() != null ? description.getMethodName() : "classMethod";
      return report.caseFor(description.getClassName(), method);
    }

    @Override
    public void testStarted(Description description) {
      startNanos.put(caseFor(description), System.nanoTime());
    }

    @Override
    public void testFinished(Description description) {
      JUnitXml.Case c = caseFor(description);
      c.seconds = (System.nanoTime() - startNanos.getOrDefault(c, System.nanoTime())) / 1e9;
    }

    @Override
    public void testFailure(Failure failure) {
      JUnitXml.Case c = caseFor(failure.getDescription());
      c.failureMessage = String.valueOf(failure.getMessage());
      c.failureDetails = failure.getTrace();
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
      report.write(path);
    }
  }
}
