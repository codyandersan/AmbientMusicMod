package com.google.android.as.oss.http.service;

import android.util.Log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic log of every request handled by the Astrea HTTP service. Written to logcat (tag
 * "AstreaHttp") and kept in an in-memory ring buffer that the Download diagnostics screen reads.
 * The buffer lives in the AMM process, so it is lost if Android kills the process.
 */
public final class AstreaRequestLog {

  public static final String TAG = "AstreaHttp";
  private static final int MAX_LINES = 1000;
  private static final ArrayDeque<String> LINES = new ArrayDeque<>();

  private AstreaRequestLog() {}

  public static void i(String message) {
    Log.i(TAG, message);
    append("I", message);
  }

  public static void w(String message, Throwable t) {
    Log.w(TAG, message, t);
    append("W", message + (t == null ? "" : " | " + t.getClass().getName() + ": " + t.getMessage()));
  }

  private static synchronized void append(String level, String message) {
    if (LINES.size() >= MAX_LINES) LINES.removeFirst();
    LINES.addLast(System.currentTimeMillis() + " " + level + " " + message);
  }

  public static synchronized List<String> snapshot() {
    return new ArrayList<>(LINES);
  }

  public static synchronized void clear() {
    LINES.clear();
  }
}
