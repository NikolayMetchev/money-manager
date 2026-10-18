package com.moneymanager.bazel.kmp.android;

import com.android.builder.testing.MockableJarGenerator;
import java.io.File;

/**
 * {@code MockableJar <android.jar> <out>}: AGP's mockable android.jar, the one its host tests run on. With
 * {@code isReturnDefaultValues = true} (the Android convention's), SDK methods return 0, false or null instead of
 * throwing "Method … not mocked.", so code that logs through {@code android.util.Log} runs in a host test.
 */
public final class MockableJar {
  private MockableJar() {}

  static void main(String[] args) throws Exception {
    if (args.length != 2) {
      throw new IllegalArgumentException("usage: MockableJar <android.jar> <out>");
    }
    new MockableJarGenerator(true).createMockableJar(new File(args[0]), new File(args[1]));
  }
}
