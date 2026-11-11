package com.zaza.housing.exception;

import com.zaza.housing.enums.ApiErrorCode;
import com.zaza.housing.util.RequestDiagnostics;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import org.springframework.http.ProblemDetail;
import tools.jackson.databind.json.JsonMapper;

public final class ApiErrors {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private ApiErrors() { }

  public static ProblemDetail problem(HttpServletRequest request, ApiException failure) {
    var problem = ProblemDetail.forStatusAndDetail(failure.status(), failure.getMessage());
    problem.setProperty("code", failure.code().name());
    problem.setProperty("requestId", RequestDiagnostics.requestId(request));
    problem.setProperty("timestamp", Instant.now().toString());
    problem.setProperty("retryable", failure.code().retryable());
    if (request != null) request.setAttribute(RequestDiagnostics.ERROR_CODE, failure.code().name());
    return problem;
  }

  public static void write(HttpServletRequest request, HttpServletResponse response, ApiException failure) throws IOException {
    var problem = problem(request, failure);
    var body = new LinkedHashMap<String, Object>();
    body.put("type", "about:blank");
    body.put("title", problem.getTitle());
    body.put("status", problem.getStatus());
    body.put("detail", problem.getDetail());
    body.putAll(problem.getProperties());
    response.setStatus(problem.getStatus());
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    response.setHeader(RequestDiagnostics.HEADER, (String) problem.getProperties().get("requestId"));
    if (problem.getStatus() == 401) response.setHeader("WWW-Authenticate", "Bearer");
    response.getWriter().write(JSON.writeValueAsString(body));
  }

  public static void write(HttpServletResponse response, ApiErrorCode code) throws IOException {
    write(null, response, new ApiException(code));
  }
}
