package com.zaza.housing.enums;

import org.springframework.http.HttpStatus;

/** Stable application codes. Clients should not parse human-readable detail text. */
public enum ApiErrorCode {
  INVALID_REQUEST(HttpStatus.BAD_REQUEST, false, "Invalid request format."),
  VALIDATION_FAILED(HttpStatus.BAD_REQUEST, false, "Check the supplied fields."),
  INVALID_FILE(HttpStatus.BAD_REQUEST, false, "The uploaded file is invalid or unsupported."),
  AUTHENTICATION_REQUIRED(HttpStatus.UNAUTHORIZED, false, "Please sign in again."),
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, false, "Email or password is incorrect."),
  ACCESS_DENIED(HttpStatus.FORBIDDEN, false, "You do not have permission to do that."),
  MFA_REQUIRED(HttpStatus.FORBIDDEN, false, "Verify your email and multi-factor authentication in Account security before using administration."),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, false, "The requested resource was not found."),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, false, "This request method is not supported."),
  NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, false, "The requested response format is not supported."),
  DATA_CONFLICT(HttpStatus.CONFLICT, false, "The record already exists or conflicts with existing data."),
  DUPLICATE_RESOURCE(HttpStatus.CONFLICT, false, "The record already exists."),
  STALE_RECORD(HttpStatus.CONFLICT, false, "The record has changed. Reload it before saving."),
  TRANSACTION_CONFLICT(HttpStatus.CONFLICT, false, "Another operation changed this data. Reload and try again."),
  LENGTH_REQUIRED(HttpStatus.LENGTH_REQUIRED, false, "The request requires a known content length."),
  FILE_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, false, "The uploaded file is too large."),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, false, "This content type is not supported."),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true, "Too many requests. Wait before trying again."),
  DATABASE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "The database is temporarily unavailable. Please try again."),
  DATABASE_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false, "The database operation could not be completed."),
  AUTHENTICATION_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "Authentication is temporarily unavailable. Please try again."),
  STORAGE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "File storage is temporarily unavailable. Please try again."),
  EMAIL_DELIVERY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "Account email delivery is temporarily unavailable."),
  MEDIA_PROCESSING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "Media processing is temporarily unavailable."),
  UPSTREAM_UNAVAILABLE(HttpStatus.BAD_GATEWAY, true, "A required external service is temporarily unavailable."),
  UPSTREAM_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, true, "A required external service did not respond in time."),
  SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true, "The service is temporarily unavailable. Please try again."),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false, "An unexpected error occurred. Contact support with the request ID.");

  private final HttpStatus status;
  private final boolean retryable;
  private final String detail;

  ApiErrorCode(HttpStatus status, boolean retryable, String detail) {
    this.status = status;
    this.retryable = retryable;
    this.detail = detail;
  }

  public HttpStatus status() { return status; }
  public boolean retryable() { return retryable; }
  public String detail() { return detail; }

  public static ApiErrorCode fromStatus(int status) {
    return switch (status) {
      case 400, 422 -> INVALID_REQUEST;
      case 401 -> AUTHENTICATION_REQUIRED;
      case 403 -> ACCESS_DENIED;
      case 404 -> RESOURCE_NOT_FOUND;
      case 405 -> METHOD_NOT_ALLOWED;
      case 406 -> NOT_ACCEPTABLE;
      case 409 -> DATA_CONFLICT;
      case 411 -> LENGTH_REQUIRED;
      case 413 -> FILE_TOO_LARGE;
      case 415 -> UNSUPPORTED_MEDIA_TYPE;
      case 429 -> RATE_LIMITED;
      case 502 -> UPSTREAM_UNAVAILABLE;
      case 503 -> SERVICE_UNAVAILABLE;
      case 504 -> UPSTREAM_TIMEOUT;
      default -> INTERNAL_ERROR;
    };
  }
}
