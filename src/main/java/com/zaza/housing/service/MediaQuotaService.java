package com.zaza.housing.service;

import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.RetainedMediaUsage;
import com.zaza.housing.repository.MediaQuotaRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaQuotaService {
  private final MediaQuotaRepository quotas;
  private final long accountBytes, organizationBytes;

  public MediaQuotaService(MediaQuotaRepository quotas,
      @Value("${zaza.media.account-bytes:1073741824}") long accountBytes,
      @Value("${zaza.media.organization-bytes:5368709120}") long organizationBytes) {
    if (accountBytes < 1 || organizationBytes < 1)
      throw new IllegalArgumentException("Retained media quotas must be positive.");
    this.quotas = quotas;
    this.accountBytes = accountBytes;
    this.organizationBytes = organizationBytes;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireCapacity(UUID actor, UUID ownerOrganization, long bytes) {
    // Every uploader locks these scopes in this order, including delegated agencies.
    // The transaction retains the locks until the media row and audit event commit.
    quotas.lock("account:" + actor);
    quotas.lock("organization:" + ownerOrganization);
    check(quotas.usage(actor, false), bytes, accountBytes, 1000, 100);
    check(quotas.usage(ownerOrganization, true), bytes, organizationBytes, 5000, 500);
  }

  private void check(RetainedMediaUsage usage, long bytes, long maximum, int objects, int daily) {
    if (usage.recentUploads() >= daily)
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "The daily media upload limit has been reached.");
    if (bytes < 1 || bytes > maximum || usage.bytes() > maximum - bytes || usage.objects() >= objects)
      throw new ApiException(HttpStatus.CONFLICT,
          "Retained media storage is full. Archived media also counts toward this limit.");
  }
}
