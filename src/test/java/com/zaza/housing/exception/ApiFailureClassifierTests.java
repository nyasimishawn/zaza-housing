package com.zaza.housing.exception;

import static org.assertj.core.api.Assertions.*;

import com.zaza.housing.enums.ApiErrorCode;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

class ApiFailureClassifierTests {
  private static final String SECRET = "password=private-token user@example.test SELECT private_data";

  @Test void databaseFailuresHaveDistinctCodesWithoutSqlDetails() {
    check(new CannotGetJdbcConnectionException(SECRET), ApiErrorCode.DATABASE_UNAVAILABLE, 503);
    check(new DuplicateKeyException(SECRET), ApiErrorCode.DUPLICATE_RESOURCE, 409);
    check(new DataIntegrityViolationException(SECRET), ApiErrorCode.DATA_CONFLICT, 409);
    check(new OptimisticLockingFailureException(SECRET), ApiErrorCode.TRANSACTION_CONFLICT, 409);
  }

  @Test void timeoutAndUnavailableProvidersAreDifferentFailures() {
    check(new ResourceAccessException(SECRET, new SocketTimeoutException(SECRET)), ApiErrorCode.UPSTREAM_TIMEOUT, 504);
    check(new ResourceAccessException(SECRET), ApiErrorCode.UPSTREAM_UNAVAILABLE, 502);
    check(new IllegalStateException(SECRET), ApiErrorCode.INTERNAL_ERROR, 500);
  }

  @Test void existingExceptionsKeepTheirStatusAndCuratedDetail() {
    var legacy = new ApiException(HttpStatus.CONFLICT, "Reload the property before saving.");
    assertThat(ApiFailureClassifier.classify(legacy)).isSameAs(legacy);
    assertThat(legacy.code()).isEqualTo(ApiErrorCode.DATA_CONFLICT);
    assertThat(legacy.getMessage()).isEqualTo("Reload the property before saving.");
    var stale = new ApiException(ApiErrorCode.STALE_RECORD);
    assertThat(stale.status()).isEqualTo(HttpStatus.CONFLICT);
    assertThat(stale.code().retryable()).isFalse();
  }

  private void check(Exception error, ApiErrorCode code, int status) {
    var result = ApiFailureClassifier.classify(error);
    assertThat(result.code()).isEqualTo(code);
    assertThat(result.status().value()).isEqualTo(status);
    assertThat(result.getMessage()).doesNotContain(SECRET, "private-token", "SELECT", "user@example.test");
  }
}
