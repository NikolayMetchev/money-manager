package com.moneymanager.bazel.kmp.testing;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs a Kotlin/Native test executable for {@code kmp_test} and reports its results the way the JVM runner
 * does.
 *
 * <p>The executable (linked with {@code -generate-test-runner}) is the first argument. It runs with
 * {@code --ktest_logger=TEAMCITY}, whose service messages become JUnit XML in {@code $XML_OUTPUT_FILE}; its
 * output is passed through unchanged. {@code --test_filter} ({@code TESTBRIDGE_TEST_ONLY}) is a substring of
 * {@code Class#method}, as on the JVM. Written in Java rather than shell so it runs the same on every OS.
 */
public final class KnTestLauncher {
  private static final String PREFIX = "##teamcity[";

  private KnTestLauncher() {}

  public static void main(String[] args) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(Path.of(args[0]).toAbsolutePath().toString());
    command.add("--ktest_logger=TEAMCITY");
    String only = System.getenv("TESTBRIDGE_TEST_ONLY");
    if (only != null && !only.isEmpty()) {
      command.add("--ktest_regex_filter=.*" + regexLiteral(only.replace('#', '.')) + ".*");
    }
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();

    JUnitXml report = new JUnitXml();
    String suite = "";
    int failed = 0;
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        System.out.println(line);
        int start = line.indexOf(PREFIX);
        if (start < 0) {
          continue;
        }
        Map<String, String> attrs = new HashMap<>();
        String type = parse(line, start + PREFIX.length(), attrs);
        String name = attrs.getOrDefault("name", "");
        switch (type) {
          case "testSuiteStarted" -> suite = name;
          case "testSuiteFinished" -> suite = "";
          case "testStarted" -> report.caseFor(suite, name);
          case "testFinished" -> report.caseFor(suite, name).seconds =
              Long.parseLong(attrs.getOrDefault("duration", "0")) / 1000.0;
          case "testIgnored" -> report.caseFor(suite, name).skipped = true;
          case "testFailed" -> {
            JUnitXml.Case c = report.caseFor(suite, name);
            c.failureMessage = attrs.getOrDefault("message", "");
            c.failureDetails = attrs.getOrDefault("details", c.failureMessage);
            failed++;
          }
          default -> {}
        }
      }
    }
    int exit = process.waitFor();

    String xmlOutput = System.getenv("XML_OUTPUT_FILE");
    if (xmlOutput != null) {
      report.write(Path.of(xmlOutput));
    }
    System.err.printf("%d tests, %d failed%n", report.count(), failed);
    System.exit(exit != 0 ? exit : report.count() > 0 && failed == 0 ? 0 : 1);
  }

  /**
   * Parses one service message, {@code type key='value' …]}, from {@code from}: returns the type and puts
   * the unescaped attributes into {@code attrs}. Hand-written, since a regex overflows the stack on a long
   * failure message.
   */
  private static String parse(String line, int from, Map<String, String> attrs) {
    int i = from;
    while (i < line.length() && line.charAt(i) != ' ' && line.charAt(i) != ']') {
      i++;
    }
    String type = line.substring(from, i);
    while (i < line.length() && line.charAt(i) != ']') {
      if (line.charAt(i) == ' ') {
        i++;
        continue;
      }
      int eq = line.indexOf('=', i);
      if (eq < 0 || eq + 1 >= line.length() || line.charAt(eq + 1) != '\'') {
        break;
      }
      String key = line.substring(i, eq);
      StringBuilder value = new StringBuilder();
      i = eq + 2;
      while (i < line.length() && line.charAt(i) != '\'') {
        if (line.charAt(i) == '|' && i + 1 < line.length()) {
          value.append(line, i, i + 2);
          i += 2;
        } else {
          value.append(line.charAt(i++));
        }
      }
      attrs.put(key, unescape(value.toString()));
      i++;
    }
    return type;
  }

  /** TeamCity's escapes: {@code |'}, {@code ||}, {@code |n}, {@code |r}, {@code |[}, {@code |]}, {@code |0xXXXX}. */
  private static String unescape(String value) {
    StringBuilder sb = new StringBuilder(value.length());
    for (int i = 0; i < value.length(); i++) {
      char ch = value.charAt(i);
      if (ch != '|' || i + 1 == value.length()) {
        sb.append(ch);
        continue;
      }
      char next = value.charAt(++i);
      switch (next) {
        case 'n' -> sb.append('\n');
        case 'r' -> sb.append('\r');
        case 'x' -> sb.append('\u0085');
        case 'l' -> sb.append(' ');
        case 'p' -> sb.append(' ');
        case '0' -> {
          if (i + 5 < value.length() && value.charAt(i + 1) == 'x') {
            sb.append((char) Integer.parseInt(value.substring(i + 2, i + 6), 16));
            i += 5;
          } else {
            sb.append(next);
          }
        }
        default -> sb.append(next);
      }
    }
    return sb.toString();
  }

  private static String regexLiteral(String text) {
    StringBuilder sb = new StringBuilder();
    for (char ch : text.toCharArray()) {
      if ("\\^$.|?*+()[]{}".indexOf(ch) >= 0) {
        sb.append('\\');
      }
      sb.append(ch);
    }
    return sb.toString();
  }
}
