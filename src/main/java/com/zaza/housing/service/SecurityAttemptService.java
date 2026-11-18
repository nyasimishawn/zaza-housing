package com.zaza.housing.service;

import com.zaza.housing.exception.ApiException;
import com.zaza.housing.repository.AccountSecurityRepository;
import com.zaza.housing.util.TokenUtils;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class SecurityAttemptService {
  private static final String LOGIN_FAILURE = "LOGIN_FAILURE";
  private final AccountSecurityRepository repository;
  private final Clock clock;

  public SecurityAttemptService(AccountSecurityRepository repository, Clock clock) {
    this.repository = repository;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean allow(String subject, String purpose, int max) {
    long window = clock.instant().getEpochSecond() / 600;
    int count = repository.attempt(TokenUtils.hashToken(subject), purpose, window);
    return count <= max;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
  public void require(String subject, String purpose, int max) {
    if (!allow(subject, purpose, max)) throw limitReached();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public long checkLoginFailures(String subject) {
    String hash = TokenUtils.hashToken(subject);
    repository.lockAttempts(hash, LOGIN_FAILURE);
    long window = clock.instant().getEpochSecond() / 600;
    if (repository.attemptCount(hash, LOGIN_FAILURE, window) >= 20) throw limitReached();
    return window;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void recordLoginFailure(String subject, long window) {
    repository.attempt(TokenUtils.hashToken(subject), LOGIN_FAILURE, window);
  }

  private static ApiException limitReached() {
    return new ApiException(
        HttpStatus.TOO_MANY_REQUESTS, "Too many security attempts. Try again in ten minutes.");
  }
}
