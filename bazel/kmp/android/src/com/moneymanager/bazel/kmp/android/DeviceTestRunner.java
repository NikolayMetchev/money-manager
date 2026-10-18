package com.moneymanager.bazel.kmp.android;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A self-instrumenting test APK's tests on a connected device, as {@code connectedAndroidDeviceTest} runs them:
 * install, {@code am instrument -w -r} with the APK's runner, uninstall. adb picks the device ({@code
 * ANDROID_SERIAL} when several are connected). The raw instrumentation output becomes Bazel's test.xml, one
 * testcase per test, and any failed, errored or crashed test (or a run that never finished) fails the target.
 *
 * <p>Arguments: {@code <adb> <apk> <package> <runner> [<app package>=<app apk> …]}: the apps are installed first and
 * uninstalled after (the app a {@code com.android.test} module drives).
 */
public final class DeviceTestRunner {
  private DeviceTestRunner() {}

  /** One test's status blocks: its start (code 1) and its end (0 passed, -3 ignored, -4 assumption, else failed). */
  private record Result(String className, String name, int code, String stack, long millis) {}

  public static void main(String[] args) throws Exception {
    String adb = Path.of(args[0]).toAbsolutePath().toString();
    String apk = Path.of(args[1]).toAbsolutePath().toString();
    String pkg = args[2];
    String runner = args[3];
    Map<String, String> apps = new LinkedHashMap<>();
    for (int i = 4; i < args.length; i++) {
      String[] app = args[i].split("=", 2);
      apps.put(app[0], Path.of(app[1]).toAbsolutePath().toString());
    }
    for (String app : apps.values()) {
      run(List.of(adb, "install", "-r", "-t", app), true);
    }
    run(List.of(adb, "install", "-r", "-t", apk), true);
    List<Result> results = new ArrayList<>();
    Integer code;
    String failure;
    try {
      Process process = new ProcessBuilder(adb, "shell", "am", "instrument", "-w", "-r", pkg + "/" + runner)
          .redirectErrorStream(true)
          .start();
      List<String> lines = new ArrayList<>();
      try (BufferedReader in = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = in.readLine()) != null) {
          System.out.println(line);
          lines.add(line);
        }
      }
      process.waitFor();
      Parsed parsed = parse(lines);
      results = parsed.results;
      code = parsed.code;
      failure = parsed.failure;
    } finally {
      run(List.of(adb, "uninstall", pkg), false);
      for (String app : apps.keySet()) {
        run(List.of(adb, "uninstall", app), false);
      }
    }
    long failed = results.stream().filter(r -> isFailure(r.code)).count();
    writeXml(pkg, results, failure);
    System.out.printf("%d tests, %d failed%n", results.size(), failed);
    // -1 is Activity.RESULT_OK: the runner finished. A crash leaves INSTRUMENTATION_FAILED and no code.
    if (failed > 0 || code == null || code != -1 || failure != null || results.isEmpty()) {
      if (failure != null) {
        System.out.println("Instrumentation failed: " + failure);
      }
      System.exit(1);
    }
  }

  private static boolean isFailure(int code) {
    return code != 0 && code != -3 && code != -4;
  }

  private record Parsed(List<Result> results, Integer code, String failure) {}

  /**
   * {@code am instrument -r}: {@code INSTRUMENTATION_STATUS: key=value} lines (a value runs on until the next
   * {@code INSTRUMENTATION_} line, as stack traces do), each block closed by {@code INSTRUMENTATION_STATUS_CODE}, then
   * {@code INSTRUMENTATION_RESULT} lines and {@code INSTRUMENTATION_CODE}.
   */
  private static Parsed parse(List<String> lines) {
    List<Result> results = new ArrayList<>();
    Map<String, StringBuilder> status = new LinkedHashMap<>();
    StringBuilder current = null;
    long started = 0;
    Integer code = null;
    String failure = null;
    for (String line : lines) {
      if (line.startsWith("INSTRUMENTATION_STATUS: ") || line.startsWith("INSTRUMENTATION_RESULT: ")) {
        String pair = line.substring(line.indexOf(": ") + 2);
        int eq = pair.indexOf('=');
        current = new StringBuilder(eq < 0 ? "" : pair.substring(eq + 1));
        status.put(eq < 0 ? pair : pair.substring(0, eq), current);
        if (line.startsWith("INSTRUMENTATION_RESULT: ") && pair.startsWith("shortMsg=")) {
          failure = current.toString();
        }
      } else if (line.startsWith("INSTRUMENTATION_STATUS_CODE: ")) {
        int statusCode = Integer.parseInt(line.substring(line.indexOf(": ") + 2).trim());
        String className = value(status, "class");
        String name = value(status, "test");
        if (statusCode == 1) {
          started = System.currentTimeMillis();
        } else if (className != null && name != null) {
          results.add(new Result(className, name, statusCode, value(status, "stack"),
              System.currentTimeMillis() - started));
        }
        status.clear();
        current = null;
      } else if (line.startsWith("INSTRUMENTATION_CODE: ")) {
        code = Integer.parseInt(line.substring(line.indexOf(": ") + 2).trim());
        current = null;
      } else if (line.startsWith("INSTRUMENTATION_FAILED: ") || line.startsWith("INSTRUMENTATION_ABORTED")) {
        failure = line;
        current = null;
      } else if (current != null) {
        current.append('\n').append(line);
      }
    }
    return new Parsed(results, code, failure);
  }

  private static String value(Map<String, StringBuilder> status, String key) {
    StringBuilder value = status.get(key);
    return value == null ? null : value.toString();
  }

  private static void writeXml(String pkg, List<Result> results, String failure) throws IOException {
    String out = System.getenv("XML_OUTPUT_FILE");
    if (out == null) {
      return;
    }
    StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<testsuites>\n");
    long failed = results.stream().filter(r -> isFailure(r.code)).count();
    long skipped = results.stream().filter(r -> r.code == -3 || r.code == -4).count();
    xml.append(String.format("  <testsuite name=\"%s\" tests=\"%d\" failures=\"%d\" skipped=\"%d\" errors=\"%d\">%n",
        escape(pkg), results.size(), failed, skipped, failure == null ? 0 : 1));
    for (Result r : results) {
      xml.append(String.format("    <testcase classname=\"%s\" name=\"%s\" time=\"%.3f\"",
          escape(r.className), escape(r.name), r.millis / 1000.0));
      if (isFailure(r.code)) {
        xml.append(">\n      <failure message=\"").append(escape(firstLine(r.stack))).append("\">")
            .append(escape(r.stack == null ? "" : r.stack)).append("</failure>\n    </testcase>\n");
      } else if (r.code == -3 || r.code == -4) {
        xml.append(">\n      <skipped/>\n    </testcase>\n");
      } else {
        xml.append("/>\n");
      }
    }
    if (failure != null) {
      xml.append("    <error message=\"").append(escape(failure)).append("\"/>\n");
    }
    xml.append("  </testsuite>\n</testsuites>\n");
    Files.writeString(Path.of(out), xml);
  }

  private static String firstLine(String text) {
    if (text == null) {
      return "";
    }
    int newline = text.indexOf('\n');
    return newline < 0 ? text : text.substring(0, newline);
  }

  private static String escape(String text) {
    StringBuilder out = new StringBuilder();
    for (char c : text.toCharArray()) {
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        default -> {
          if (c >= 0x20 || c == '\n' || c == '\t' || c == '\r') {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }

  private static void run(List<String> command, boolean check) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(command).inheritIO().start();
    int exit = process.waitFor();
    if (check && exit != 0) {
      throw new IllegalStateException(String.join(" ", command) + " exited " + exit);
    }
  }
}
