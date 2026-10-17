package com.moneymanager.bazel.kmp.testing;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The JUnit XML report Bazel reads from {@code $XML_OUTPUT_FILE}: one testsuite per class, in the order the
 * cases were first seen. Shared by the JVM and the Kotlin/Native test runners, so both report the same way.
 */
final class JUnitXml {
  static final class Case {
    final String name;
    double seconds;
    String failureMessage;
    String failureDetails;
    boolean skipped;

    Case(String name) {
      this.name = name;
    }

    boolean failed() {
      return failureDetails != null;
    }
  }

  private final Map<String, Map<String, Case>> suites = new LinkedHashMap<>();

  Case caseFor(String className, String method) {
    return suites.computeIfAbsent(className, k -> new LinkedHashMap<>()).computeIfAbsent(method, Case::new);
  }

  int count() {
    return suites.values().stream().mapToInt(Map::size).sum();
  }

  void write(Path path) throws IOException {
    StringWriter out = new StringWriter();
    out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
    for (Map.Entry<String, Map<String, Case>> suite : suites.entrySet()) {
      int failures = 0;
      int skipped = 0;
      double seconds = 0;
      for (Case c : suite.getValue().values()) {
        failures += c.failed() ? 1 : 0;
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
        if (c.failed()) {
          out.write(">\n      <failure message=\"");
          out.write(escape(String.valueOf(c.failureMessage)));
          out.write("\">");
          out.write(escape(c.failureDetails));
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
