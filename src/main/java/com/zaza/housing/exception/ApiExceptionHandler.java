package com.zaza.housing.exception;

import com.zaza.housing.enums.ApiErrorCode;
import com.zaza.housing.util.RequestDiagnostics;
import com.zaza.housing.util.SafeFailureLog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
  @ExceptionHandler(ApiException.class)
  ResponseEntity<Object> applicationError(ApiException error, HttpServletRequest request) {
    return respond(request, error, error, new HttpHeaders());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<Object> constraintError(ConstraintViolationException error, HttpServletRequest request) {
    return respond(request, new ApiException(ApiErrorCode.VALIDATION_FAILED), error, new HttpHeaders());
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Object> unexpectedError(Exception error, HttpServletRequest request) {
    return respond(request, ApiFailureClassifier.classify(error), error, new HttpHeaders());
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException error,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    var response = respond(servlet(request), new ApiException(ApiErrorCode.VALIDATION_FAILED), error, headers);
    var problem = (org.springframework.http.ProblemDetail) response.getBody();
    // Preserve the existing field-error contract without returning rejected values.
    problem.setProperty("errors", error.getBindingResult().getFieldErrors().stream()
        .map(field -> field.getField() + ": " + field.getDefaultMessage()).distinct().toList());
    return response;
  }

  @Override
  protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException error,
      HttpHeaders headers, HttpStatusCode status, WebRequest request) {
    return respond(servlet(request), new ApiException(ApiErrorCode.FILE_TOO_LARGE,
        "Files must be at most 50 MB; the upload request must be at most 51 MB. Images remain limited to 10 MB."), error, headers);
  }

  @Override
  protected ResponseEntity<Object> handleExceptionInternal(Exception error, Object body, HttpHeaders headers,
      HttpStatusCode status, WebRequest request) {
    // Framework exception bodies can include parser input, resource paths, or Java type names.
    var code = ApiErrorCode.fromStatus(status.value());
    return respond(servlet(request), new ApiException(HttpStatus.valueOf(status.value()), code.detail()), error, headers);
  }

  private ResponseEntity<Object> respond(HttpServletRequest request, ApiException failure,
      Exception original, HttpHeaders originalHeaders) {
    var problem = ApiErrors.problem(request, failure);
    var headers = new HttpHeaders();
    headers.addAll(originalHeaders);
    headers.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
    headers.setCacheControl("no-store");
    headers.set(RequestDiagnostics.HEADER, RequestDiagnostics.requestId(request));
    if (failure.status() == HttpStatus.UNAUTHORIZED) headers.set("WWW-Authenticate", "Bearer");
    if (failure.status().is5xxServerError()) SafeFailureLog.record(original, failure.code());
    return new ResponseEntity<>(problem, headers, failure.status());
  }

  private static HttpServletRequest servlet(WebRequest request) {
    return ((ServletWebRequest) request).getRequest();
  }
}
