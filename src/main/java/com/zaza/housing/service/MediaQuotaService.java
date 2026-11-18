package com.zaza.housing.service;

import com.zaza.housing.dto.MediaQuotaScopeResponse;
import com.zaza.housing.dto.PropertyMediaQuotaResponse;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.RetainedMediaUsage;
import com.zaza.housing.repository.MediaQuotaRepository;
import com.zaza.housing.repository.PropertyImageRepository;
import com.zaza.housing.repository.PropertyVideoRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MediaQuotaService {
  static final int ACTIVE_IMAGES = 30;
  static final int RETAINED_IMAGES = 60;
  static final int ACTIVE_VIDEOS = 5;
  static final int RETAINED_VIDEOS = 10;
  private static final int ACCOUNT_OBJECTS = 1000;
  private static final int ACCOUNT_DAILY = 100;
  private static final int ORGANIZATION_OBJECTS = 5000;
  private static final int ORGANIZATION_DAILY = 500;
  private final MediaQuotaRepository quotas;
  private final PropertyImageRepository images;
  private final PropertyVideoRepository videos;
  private final long accountBytes, organizationBytes;

  public MediaQuotaService(MediaQuotaRepository quotas, PropertyImageRepository images,
      PropertyVideoRepository videos,
      @Value("${zaza.media.account-bytes:1073741824}") long accountBytes,
      @Value("${zaza.media.organization-bytes:5368709120}") long organizationBytes) {
    if (accountBytes < 1 || organizationBytes < 1)
      throw new IllegalArgumentException("Retained media quotas must be positive.");
    this.quotas = quotas;
    this.images = images;
    this.videos = videos;
    this.accountBytes = accountBytes;
    this.organizationBytes = organizationBytes;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void requireCapacity(UUID actor, UUID ownerOrganization, long bytes) {
    // Every uploader locks these scopes in this order, including delegated agencies.
    // The transaction retains the locks until the media row and audit event commit.
    quotas.lock("account:" + actor);
    quotas.lock("organization:" + ownerOrganization);
    check(accountUsage(actor), bytes);
    check(organizationUsage(ownerOrganization), bytes);
  }

  private MediaQuotaScopeResponse accountUsage(UUID actor) {
    return new MediaQuotaScopeResponse(
        quotas.usage(actor, false), accountBytes, ACCOUNT_OBJECTS, ACCOUNT_DAILY);
  }

  private MediaQuotaScopeResponse organizationUsage(UUID owner) {
    return new MediaQuotaScopeResponse(
        quotas.usage(owner, true), organizationBytes, ORGANIZATION_OBJECTS, ORGANIZATION_DAILY);
  }

  PropertyMediaQuotaResponse summary(UUID actor, UUID property, UUID owner, boolean revealOwnerUsage) {
    return new PropertyMediaQuotaResponse(
        accountUsage(actor),
        revealOwnerUsage ? organizationUsage(owner) : null,
        images.activeCount(property), ACTIVE_IMAGES,
        images.retainedCount(property), RETAINED_IMAGES,
        videos.activeCount(property), ACTIVE_VIDEOS,
        videos.retainedCount(property), RETAINED_VIDEOS);
  }

  private void check(MediaQuotaScopeResponse scope, long bytes) {
    RetainedMediaUsage usage = scope.usage();
    if (usage.recentUploads() >= scope.dailyUploadLimit())
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "The daily media upload limit has been reached.");
    if (bytes < 1 || bytes > scope.byteLimit() || usage.bytes() > scope.byteLimit() - bytes
        || usage.objects() >= scope.objectLimit())
      throw new ApiException(HttpStatus.CONFLICT,
          "Retained media storage is full. Archived media also counts toward this limit.");
  }
}
