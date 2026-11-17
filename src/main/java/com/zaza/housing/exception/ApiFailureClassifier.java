package com.zaza.housing.exception;

import com.zaza.housing.enums.ApiErrorCode;
import jakarta.servlet.ServletException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.web.client.RestClientException;

public final class ApiFailureClassifier {
  private ApiFailureClassifier() { }

  public static ApiException classify(Exception error) {
    if (error instanceof ApiException known) return known;
    if (error instanceof ServletException && error.getCause() instanceof Exception cause) return classify(cause);
    ApiErrorCode code;
    if (error instanceof AuthenticationException) code = ApiErrorCode.AUTHENTICATION_REQUIRED;
    else if (error instanceof AccessDeniedException) code = ApiErrorCode.ACCESS_DENIED;
    else if (error instanceof DuplicateKeyException) code = ApiErrorCode.DUPLICATE_RESOURCE;
    else if (error instanceof DataIntegrityViolationException) code = ApiErrorCode.DATA_CONFLICT;
    else if (error instanceof ConcurrencyFailureException) code = ApiErrorCode.TRANSACTION_CONFLICT;
    else if (error instanceof DataAccessResourceFailureException
        || error instanceof TransientDataAccessResourceException
        || error instanceof RecoverableDataAccessException
        || error instanceof CannotCreateTransactionException
        || error instanceof TransactionTimedOutException) code = ApiErrorCode.DATABASE_UNAVAILABLE;
    else if (error instanceof DataAccessException) code = ApiErrorCode.DATABASE_ERROR;
    else if (error instanceof RestClientException) code = timeout(error) ? ApiErrorCode.UPSTREAM_TIMEOUT : ApiErrorCode.UPSTREAM_UNAVAILABLE;
    else code = ApiErrorCode.INTERNAL_ERROR;
    return new ApiException(code);
  }

  private static boolean timeout(Throwable error) {
    for (int depth = 0; error != null && depth < 12; depth++, error = error.getCause()) {
      if (error instanceof SocketTimeoutException || error instanceof HttpTimeoutException) return true;
    }
    return false;
  }
}
