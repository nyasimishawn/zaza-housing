package com.zaza.housing.util;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.slf4j.MDC;

public final class RequestDiagnostics {
  public static final String HEADER = "X-Request-ID";
  public static final String MDC_KEY = "requestId";
  public static final String REQUEST_ID = RequestDiagnostics.class.getName() + ".id";
  public static final String ERROR_CODE = RequestDiagnostics.class.getName() + ".errorCode";
  private RequestDiagnostics() { }

  public static String requestId(HttpServletRequest request) {
    if (request != null && request.getAttribute(REQUEST_ID) instanceof String id) return id;
    String id = MDC.get(MDC_KEY);
    if (id == null) id = UUID.randomUUID().toString();
    if (request != null) request.setAttribute(REQUEST_ID, id);
    return id;
  }
}
