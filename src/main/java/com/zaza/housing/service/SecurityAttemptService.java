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
    if (!allow(subject, purpose, max))
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS, "Too many security attempts. Try again in ten minutes.");
  }
}
