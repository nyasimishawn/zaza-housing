# Application errors and request diagnostics

The backend returns application codes as well as HTTP statuses. Existing `type`,
`title`, `status`, `detail`, and validation `errors` fields remain available.
Additional fields are `code`, `requestId`, `timestamp` (UTC), and `retryable`.

An authentication-provider outage, for example, returns HTTP 503:

```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Keycloak authentication is unavailable. Check the local container and backend configuration.",
  "code": "AUTHENTICATION_UNAVAILABLE",
  "requestId": "aee58598-923c-4b09-85ab-268869a75102",
  "timestamp": "2026-11-11T09:15:00Z",
  "retryable": true
}
```

Application responses carry the server-generated `X-Request-ID`. Error bodies carry
the same identifier, including security-filter and bounded-upload errors. The
header is exposed to approved browser origins. Incoming request-ID headers are
not trusted as server-generated identifiers. Include the returned ID when
reporting a failure; do not report passwords or session tokens.

## Error catalogue

| Code | Meaning / caller action |
| --- | --- |
| `INVALID_REQUEST` | Malformed JSON, missing request data, or invalid parameter format. |
| `VALIDATION_FAILED` | A field violates request constraints; inspect the existing `errors` array. |
| `INVALID_FILE` | Invalid or unsupported image content; select a valid file. |
| `AUTHENTICATION_REQUIRED` | Missing, invalid, or expired sign-in; authenticate again. |
| `INVALID_CREDENTIALS` | Sign-in credentials were rejected; this does not reveal whether an account exists. |
| `ACCESS_DENIED` | The current account cannot perform the operation. |
| `MFA_REQUIRED` | Complete the existing administration email/MFA gate. |
| `RESOURCE_NOT_FOUND` | The resource is unavailable in the caller's scope. |
| `METHOD_NOT_ALLOWED`, `NOT_ACCEPTABLE`, `UNSUPPORTED_MEDIA_TYPE` | Correct the HTTP method or content/response format. |
| `DATA_CONFLICT`, `DUPLICATE_RESOURCE` | The write conflicts with existing data; review the input. |
| `STALE_RECORD` | Inventory changed since it was loaded; reload before saving or publishing. |
| `TRANSACTION_CONFLICT` | Concurrent database work prevented the operation; reload its state. |
| `LENGTH_REQUIRED`, `FILE_TOO_LARGE` | Supply a bounded request or a smaller file. |
| `RATE_LIMITED` | Respect `Retry-After` where present. |
| `DATABASE_UNAVAILABLE` | Connection or transaction infrastructure is temporarily unavailable. |
| `DATABASE_ERROR` | A database operation failed unexpectedly; report the request ID. |
| `AUTHENTICATION_UNAVAILABLE` | The external authentication provider is unavailable. |
| `STORAGE_UNAVAILABLE` | A file-storage operation failed. |
| `EMAIL_DELIVERY_UNAVAILABLE` | Account email delivery is unavailable or unconfigured. |
| `MEDIA_PROCESSING_UNAVAILABLE` | Media processing cannot accept the operation right now. |
| `UPSTREAM_UNAVAILABLE`, `UPSTREAM_TIMEOUT` | An uncaught external-service request failed or timed out. |
| `SERVICE_UNAVAILABLE` | Other existing service-unavailable failures. |
| `INTERNAL_ERROR` | Unexpected server failure; report its request ID. |

`retryable` describes a potentially temporary failure. It does not make a write
idempotent: check the result of payments and other commands before resubmitting.
Stale records and data conflicts require state review rather than blind retries.

Use `new ApiException(ApiErrorCode.STALE_RECORD, "Reload the property.")` for a
curated domain failure. The existing `(HttpStatus, String)` constructor still
works and assigns a general code for that status. Explicit codes are applied to
authentication, inventory versions, image validation, and storage failures.

The exception advice also maps database conflicts/outages, external-service
timeouts, framework request failures, and unexpected exceptions. Request-filter
failures receive the same safe contract when a response has not been committed.
Already-committed responses cannot be replaced with JSON.

## Logs

Spring Boot emits structured ECS JSON to the console and `logs/zaza.log` by
default. The log directory is ignored by Git. Configure the file with
`ZAZA_LOG_FILE`; choose another supported console JSON format with
`ZAZA_LOG_FORMAT` (`ecs`, `logstash`, or `gelf`). Files roll at 10 MB with a
14-day history and a 200 MB total archive cap.

Each `http_request` event contains `requestId`, method, matched route template,
status, duration in milliseconds, and application code. Requests without a
matched MVC route use `UNMAPPED`; security rejections and unknown routes do not
log raw URL path values. Successful requests log at INFO, rejected requests at
WARN, and server failures at ERROR. Async dispatch logs once on completion.

An `application_failure` event records a code, exception/cause class names, and
bounded stack frames. Exception messages can contain SQL values or provider
URLs, so those messages are excluded. New diagnostics do not record request or
response bodies, authorization/cookie headers, query strings, IP addresses,
raw path identifiers, passwords, tokens, or rejected field values. Thread-local
request context is restored after each request.

Search the returned request ID in the JSON log to find its request and failure
events. Database credentials and upstream provider responses are not returned
to clients. Existing authorization, CORS allowlists, and rate limits are unchanged.

Run the PostgreSQL integration suite in the isolated `zaza_foundation_test` schema:

```powershell
.\scripts\test-foundation.ps1 -UseLocalDatabase
```
