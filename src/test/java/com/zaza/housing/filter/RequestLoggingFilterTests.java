package com.zaza.housing.filter;

import static org.assertj.core.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaza.housing.security.SecurityErrors;
import com.zaza.housing.util.RequestDiagnostics;
import com.zaza.housing.util.SafeFailureLog;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;
import tools.jackson.databind.json.JsonMapper;

class RequestLoggingFilterTests {
  private final Logger requestLog = (Logger) LoggerFactory.getLogger(RequestLoggingFilter.class);
  private final Logger failureLog = (Logger) LoggerFactory.getLogger(SafeFailureLog.class);
  private final ListAppender<ILoggingEvent> events = new ListAppender<>() {
    @Override protected void append(ILoggingEvent event) {
      event.prepareForDeferredProcessing();
      super.append(event);
    }
  };
  private final JsonMapper json = JsonMapper.builder().build();
  private Level previousLevel;

  @BeforeEach void captureLogs() {
    previousLevel = requestLog.getLevel();
    requestLog.setLevel(Level.INFO);
    events.start();
    requestLog.addAppender(events);
    failureLog.addAppender(events);
  }

  @AfterEach void cleanup() {
    requestLog.detachAppender(events);
    failureLog.detachAppender(events);
    requestLog.setLevel(previousLevel);
    events.stop();
    MDC.clear();
  }

  @Test void idsCorrelateSecurityErrorsAndLogsWithoutTrustingIncomingIds() throws Exception {
    String supplied = UUID.randomUUID().toString();
    var request = new MockHttpServletRequest("GET", "/api/v1/me");
    request.addHeader(RequestDiagnostics.HEADER, supplied);
    request.addHeader("Authorization", "Bearer secret-token");
    var response = new MockHttpServletResponse();
    MDC.setContextMap(Map.of("requestId", "outer-context", "job", "previous"));
    new RequestLoggingFilter().doFilter(request, response, (req, res) -> SecurityErrors.write((HttpServletResponse) res, 401));
    var body = json.readTree(response.getContentAsString());
    String id = response.getHeader(RequestDiagnostics.HEADER);
    assertThat(UUID.fromString(id)).isNotNull();
    assertThat(id).isNotEqualTo(supplied);
    assertThat(body.get("requestId").asText()).isEqualTo(id);
    assertThat(body.get("code").asText()).isEqualTo("AUTHENTICATION_REQUIRED");
    assertThat(response.getHeader("WWW-Authenticate")).isEqualTo("Bearer");
    assertThat(events.list).hasSize(1);
    assertThat(events.list.getFirst().getMDCPropertyMap().get("requestId")).isEqualTo(id);
    assertThat(MDC.getCopyOfContextMap()).containsEntry("requestId", "outer-context").containsEntry("job", "previous");
    assertThat(logText()).doesNotContain(supplied, "secret-token");
  }

  @Test void rawPathsQueriesHeadersBodiesAndExceptionMessagesNeverEnterDiagnostics() throws Exception {
    String secret = "private-password-user@example.test";
    var request = new MockHttpServletRequest("POST", "/api/v1/private/" + secret);
    request.setQueryString("token=" + secret);
    request.addHeader("Authorization", secret);
    request.setContent(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    request.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/private/{id}");
    var response = new MockHttpServletResponse();
    new RequestLoggingFilter().doFilter(request, response, (req, res) -> {
      throw new CannotGetJdbcConnectionException("SELECT password=" + secret);
    });
    var body = json.readTree(response.getContentAsString());
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(body.get("code").asText()).isEqualTo("DATABASE_UNAVAILABLE");
    assertThat(body.get("retryable").asBoolean()).isTrue();
    assertThat(response.getContentAsString()).doesNotContain(secret, "SELECT", "CannotGetJdbcConnectionException");
    assertThat(logText()).contains("DATABASE_UNAVAILABLE", "/api/v1/private/{id}").doesNotContain(secret, "SELECT");
    assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
    assertThat(MDC.get(RequestDiagnostics.MDC_KEY)).isNull();
  }

  @Test void storageCauseTypesAndFramesSurviveWithoutLeakingTheirMessages() throws Exception {
    String secret = "private-storage-path-password-user@example.test";
    var denied = new java.nio.file.AccessDeniedException(secret);
    denied.setStackTrace(new StackTraceElement[] {
        new StackTraceElement("com.zaza.storage.Disk", "save", "Disk.java", 42)
    });
    var io = new java.io.IOException("Storage location: " + secret, denied);
    var failure = new com.zaza.housing.exception.ApiException(
        com.zaza.housing.enums.ApiErrorCode.STORAGE_UNAVAILABLE, "Storage is temporarily unavailable.", io);
    var request = new MockHttpServletRequest("POST", "/upload");
    var response = new MockHttpServletResponse();
    new RequestLoggingFilter().doFilter(request, response, (req, res) -> { throw failure; });
    var body = json.readTree(response.getContentAsString());
    String requestId = response.getHeader(RequestDiagnostics.HEADER);
    assertThat(response.getStatus()).isEqualTo(503);
    assertThat(body.get("code").asText()).isEqualTo("STORAGE_UNAVAILABLE");
    assertThat(body.get("requestId").asText()).isEqualTo(requestId);
    assertThat(body.get("detail").asText()).isEqualTo("Storage is temporarily unavailable.");
    assertThat(response.getContentAsString()).doesNotContain(secret, "IOException", "AccessDeniedException", "Disk.java");
    assertThat(logText()).contains("STORAGE_UNAVAILABLE", "java.io.IOException",
        "java.nio.file.AccessDeniedException", "com.zaza.storage.Disk.save(Disk.java:42)")
        .doesNotContain(secret, "Storage location:");
    assertThat(events.list).hasSize(2).allSatisfy(event -> {
      assertThat(event.getMDCPropertyMap()).containsEntry(RequestDiagnostics.MDC_KEY, requestId);
      assertThat(event.getThrowableProxy()).isNull();
    });
  }

  @Test void reusedThreadsReceiveDifferentRequestIds() throws Exception {
    var first = new MockHttpServletResponse();
    var second = new MockHttpServletResponse();
    var filter = new RequestLoggingFilter();
    filter.doFilter(new MockHttpServletRequest("GET", "/a"), first, (req, res) -> { });
    filter.doFilter(new MockHttpServletRequest("GET", "/b"), second, (req, res) -> { });
    assertThat(first.getHeader(RequestDiagnostics.HEADER)).isNotEqualTo(second.getHeader(RequestDiagnostics.HEADER));
    assertThat(MDC.get(RequestDiagnostics.MDC_KEY)).isNull();
  }

  @Test void asynchronousDispatchLogsOnceWhenTheRequestFinishes() throws Exception {
    var request = new MockHttpServletRequest("GET", "/async");
    request.setAsyncSupported(true);
    var response = new MockHttpServletResponse();
    var filter = new RequestLoggingFilter();
    filter.doFilter(request, response, (req, res) -> request.startAsync());
    assertThat(events.list).isEmpty();
    request.setAsyncStarted(false);
    request.setDispatcherType(DispatcherType.ASYNC);
    filter.doFilter(request, response, (req, res) -> { });
    assertThat(events.list).hasSize(1);
    assertThat(MDC.get(RequestDiagnostics.MDC_KEY)).isNull();
  }

  private String logText() {
    return events.list.stream().map(event -> event.getFormattedMessage() + " " + event.getKeyValuePairs()).collect(java.util.stream.Collectors.joining("\n"));
  }
}
