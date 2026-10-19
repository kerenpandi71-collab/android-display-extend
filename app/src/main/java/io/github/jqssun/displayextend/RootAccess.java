package io.github.jqssun.displayextend;

import android.util.Log;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Optional Magisk/su bridge for device-specific operations that are not exposed
 * by the Shizuku service. Root is requested only after the user explicitly taps
 * the root action; ordinary app startup never prompts for superuser access.
 */
public final class RootAccess {
  private static final String TAG = "ExtendRoot";

  private RootAccess() {}

  public static final class Result {
    public final boolean success;
    public final String output;

    Result(boolean success, String output) {
      this.success = success;
      this.output = output;
    }
  }

  public static Result run(String command) {
    Process process = null;
    try {
      process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
      // Wait before reading so a pending Magisk authorization prompt cannot
      // block this thread forever while readLine() waits for output.
      if (!process.waitFor(8, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return new Result(false, "Root command timed out or authorization was not completed");
      }
      StringBuilder output = new StringBuilder();
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(process.getInputStream()))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (output.length() < 4096) output.append(line).append('\n');
        }
      }
      int exitCode = process.exitValue();
      String text = output.toString().trim();
      Log.i(TAG, "root command exit=" + exitCode + " output=" + text);
      return new Result(exitCode == 0, text);
    } catch (Exception e) {
      if (process != null) process.destroyForcibly();
      Log.w(TAG, "Root access unavailable", e);
      return new Result(false, e.getMessage() == null ? "Root unavailable" : e.getMessage());
    }
  }

  public static Result check() {
    return run("id");
  }
}
