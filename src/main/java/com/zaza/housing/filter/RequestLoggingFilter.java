package com.zaza.housing.filter;

import com.zaza.housing.enums.ApiErrorCode;
import com.zaza.housing.exception.ApiErrors;
import com.zaza.housing.exception.ApiFailureClassifier;
import com.zaza.housing.util.RequestDiagnostics;
import com.zaza.housing.util.SafeFailureLog;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestLoggingFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);
  private static final String START = RequestLoggingFilter.class.getName() + ".start";
  private static final String FINISHED = RequestLoggingFilter.class.getName() + ".finished";
  private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");

  @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (request.getAttribute(RequestDiagnostics.REQUEST_ID) == null) {
      // Do not trust caller-provided IDs as server-generated diagnostic identifiers.
      request.setAttribute(RequestDiagnostics.REQUEST_ID, UUID.randomUUID().toString());
    }
    if (request.getAttribute(START) == null) request.setAttribute(START, System.nanoTime());
    var previousContext = MDC.getCopyOfContextMap();
    String requestId = RequestDiagnostics.requestId(request);
    MDC.put(RequestDiagnostics.MDC_KEY, requestId);
    response.setHeader(RequestDiagnostics.HEADER, requestId);
    try {
      chain.doFilter(request, response);
    } catch (IOException | ServletException | RuntimeException failure) {
      var classified = ApiFailureClassifier.classify(failure);
      request.setAttribute(RequestDiagnostics.ERROR_CODE, classified.code().name());
      SafeFailureLog.record(failure, classified.code());
      if (response.isCommitted()) throw failure;
      ApiErrors.write(request, response, classified);
    } finally {
      if (!request.isAsyncStarted() && request.getAttribute(FINISHED) == null) {
        request.setAttribute(FINISHED, true);
        long elapsed = (System.nanoTime() - (long) request.getAttribute(START)) / 1_000_000;
        Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        Object code = request.getAttribute(RequestDiagnostics.ERROR_CODE);
        int status = response.getStatus();
        Level level = status >= 500 ? Level.ERROR : status >= 400 ? Level.WARN : Level.INFO;
        log.atLevel(level).addKeyValue("event", "http_request")
            .addKeyValue("method", METHODS.contains(request.getMethod()) ? request.getMethod() : "OTHER")
            .addKeyValue("route", route == null ? "UNMAPPED" : route.toString())
            .addKeyValue("status", status).addKeyValue("durationMs", elapsed)
            .addKeyValue("code", code == null ? (status >= 400 ? ApiErrorCode.fromStatus(status).name() : "NONE") : code)
            .log("HTTP request completed");
      }
      if (previousContext == null) MDC.clear(); else MDC.setContextMap(previousContext);
    }
  }
}
