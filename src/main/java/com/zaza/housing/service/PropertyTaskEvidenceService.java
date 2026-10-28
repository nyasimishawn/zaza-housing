package com.zaza.housing.service;

import com.zaza.housing.dto.PropertyTaskEvidenceResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ImageContent;
import com.zaza.housing.model.PropertyTask;
import com.zaza.housing.repository.CaretakerRepository;
import com.zaza.housing.repository.LocalImageStorageRepository;
import com.zaza.housing.util.ImageProcessor;
import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class PropertyTaskEvidenceService {
  private final CaretakerAccessService access;
  private final CaretakerRepository repository;
  private final CaretakerActivityService activity;
  private final LocalImageStorageRepository storage;
  private final Clock clock;

  public PropertyTaskEvidenceService(
      CaretakerAccessService access,
      CaretakerRepository repository,
      CaretakerActivityService activity,
      LocalImageStorageRepository storage,
      Clock clock) {
    this.access = access;
    this.repository = repository;
    this.activity = activity;
    this.storage = storage;
    this.clock = clock;
  }

  public List<PropertyTaskEvidenceResponse> list(AuthenticatedUser a, UUID o, UUID id) {
    access.task(a, o, id, false);
    return repository.evidence(id);
  }

  @Transactional
  public PropertyTask upload(
      AuthenticatedUser a,
      UUID o,
      UUID id,
      UUID request,
      long version,
      String caption,
      MultipartFile file) {
    var t = access.task(a, o, id, true);
    access.active(access.property(a, o, t.propertyId(), "OPERATIONS", false));
    if (version < 0 || caption == null || caption.isBlank() || caption.length() > 200)
      throw AgencyAccessService.bad(
          "Use a caption of 1–200 characters and the current task version.");
    var image = ImageProcessor.process(file);
    String hash = hash(image.bytes());
    var body =
        Map.of(
            "requestId", request, "version", version, "caption", caption.trim(), "imageHash", hash);
    if (activity.replay(a, o, request, "PROPERTY_TASK_EVIDENCE", id, body) != null)
      return access.task(a, o, id, false);
    AgencyAccessService.version(t.version(), version);
    if (!Set.of("OPEN", "IN_PROGRESS", "BLOCKED").contains(t.status()))
      throw AgencyAccessService.conflict("Add evidence before submitting the task.");
    if (repository.evidence(id).size() >= 30)
      throw AgencyAccessService.conflict("A task can hold at most 30 evidence images.");
    final String key;
    try {
      key = storage.save(image);
    } catch (IOException e) {
      throw new com.zaza.housing.exception.ApiException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Evidence storage is unavailable.");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED)
              try {
                storage.removeUncommitted(key);
              } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger(PropertyTaskEvidenceService.class)
                    .warn("Could not remove uncommitted caretaker image: {}", key);
              }
          }
        });
    repository.evidence(
        UUID.randomUUID(), id, a.id(), caption.trim(), key, image, hash, clock.instant());
    repository.taskTouch(id, clock.instant());
    activity.record(
        a,
        o,
        request,
        t.propertyId(),
        "PROPERTY_TASK_EVIDENCE",
        "TASK",
        id,
        body,
        caption.trim(),
        t.assigneeId(),
        t.assigneeOrganizationId());
    return access.task(a, o, id, false);
  }

  private String hash(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public ImageContent content(AuthenticatedUser a, UUID o, UUID task, UUID id) {
    access.task(a, o, task, false);
    var key = repository.evidenceKey(task, id).orElseThrow(CaretakerAccessService::missing);
    var e =
        repository.evidence(task).stream()
            .filter(x -> x.id().equals(id))
            .findFirst()
            .orElseThrow(CaretakerAccessService::missing);
    try {
      return new ImageContent(id, storage.read(key), e.contentType(), e.byteSize());
    } catch (IOException ex) {
      throw CaretakerAccessService.missing();
    }
  }
}
