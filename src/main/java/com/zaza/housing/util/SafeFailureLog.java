package com.zaza.housing.util;

import com.zaza.housing.enums.ApiErrorCode;
import java.util.ArrayList;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Exception messages and causes can contain SQL values, URLs, passwords or tokens. */
public final class SafeFailureLog {
  private static final Logger log = LoggerFactory.getLogger(SafeFailureLog.class);
  private SafeFailureLog() { }

  public static void record(Exception error, ApiErrorCode code) {
    var types = new ArrayList<String>();
    var frames = new ArrayList<String>();
    Throwable current = error;
    for (int depth = 0; current != null && depth < 6; depth++, current = current.getCause()) {
      types.add(current.getClass().getName());
      Arrays.stream(current.getStackTrace()).limit(16).map(StackTraceElement::toString).forEach(frames::add);
    }
    log.atError().addKeyValue("event", "application_failure")
        .addKeyValue("code", code.name()).addKeyValue("exceptionTypes", types)
        .addKeyValue("stackFrames", frames).log("Application operation failed");
  }
}
