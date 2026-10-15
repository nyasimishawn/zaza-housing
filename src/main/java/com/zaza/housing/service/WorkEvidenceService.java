package com.zaza.housing.service;

import com.zaza.housing.dto.WorkEvidenceResponse;
import com.zaza.housing.dto.WorkView;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ImageContent;
import com.zaza.housing.repository.LocalImageStorageRepository;
import com.zaza.housing.repository.WorkEvidenceRepository;
import com.zaza.housing.util.ImageProcessor;
import com.zaza.housing.util.Pagination;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class WorkEvidenceService {
  private final WorkAccessService access;
  private final WorkCommandService commands;
  private final WorkEvidenceRepository evidence;
  private final LocalImageStorageRepository storage;
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(WorkEvidenceService.class);

  public WorkEvidenceService(
      WorkAccessService access,
      WorkCommandService commands,
      WorkEvidenceRepository evidence,
      LocalImageStorageRepository storage) {
    this.access = access;
    this.commands = commands;
    this.evidence = evidence;
    this.storage = storage;
  }

  @Transactional
  public WorkView upload(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      UUID requestId,
      long version,
      WorkVisibility visibility,
      String caption,
      MultipartFile file) {
    if (version < 0 || caption == null || caption.isBlank() || caption.length() > 200)
      throw WorkAccessService.invalid(
          "Use a nonnegative version and an evidence caption of 1-200 characters.");
    access.get(actor, party, org, property, id, true);
    var image = ImageProcessor.process(file);
    var body =
        Map.of(
            "requestId",
            requestId,
            "version",
            version,
            "visibility",
            visibility,
            "caption",
            caption.trim(),
            "imageHash",
            hash(image.bytes()));
    var m =
        commands.begin(actor, party, org, property, id, requestId, "EVIDENCE_ADDED", body, version);
    var a = m.access();
    if (m.replay()) return commands.response(a);
    if (visibility == WorkVisibility.INTERNAL && party != WorkParty.COORDINATOR)
      throw WorkAccessService.forbidden("Only property managers can store internal evidence.");
    if (party == WorkParty.PROVIDER)
      WorkAccessService.state(
          a.work(),
          WorkStatus.ACCEPTED,
          WorkStatus.SCHEDULED,
          WorkStatus.IN_PROGRESS,
          WorkStatus.AWAITING_CONFIRMATION,
          WorkStatus.AWAITING_APPROVAL,
          WorkStatus.DISPUTED);
    else if (Set.of(WorkStatus.COMPLETED, WorkStatus.CANCELLED, WorkStatus.REJECTED)
        .contains(a.work().status()))
      throw WorkAccessService.conflict("Evidence cannot be appended to closed work.");
    if (evidence.count(id) >= 30)
      throw WorkAccessService.conflict("A work request can hold at most 30 evidence images.");
    final String key;
    try {
      key = storage.save(image);
    } catch (IOException e) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Evidence storage is unavailable.");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED)
              try {
                storage.removeUncommitted(key);
              } catch (IOException e) {
                log.warn("Could not remove uncommitted work evidence: {}", key);
              }
          }
        });
    evidence.create(a, actor.id(), visibility, caption.trim(), key, image);
    return commands.finish(actor, a, requestId, "EVIDENCE_ADDED", body, caption.trim(), visibility);
  }

  public List<WorkEvidenceResponse> list(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      int limit,
      int offset) {
    var a = access.get(actor, party, org, property, id, false);
    Pagination.validate(limit, offset);
    return evidence.list(a, limit, offset).stream()
        .map(
            e ->
                new WorkEvidenceResponse(
                    e.id(),
                    e.party(),
                    e.visibility(),
                    e.caption(),
                    e.contentType(),
                    e.width(),
                    e.height(),
                    e.byteSize(),
                    e.createdAt()))
        .toList();
  }

  public ImageContent content(
      AuthenticatedUser actor, WorkParty party, UUID org, UUID property, UUID id, UUID imageId) {
    var a = access.get(actor, party, org, property, id, false);
    var e =
        evidence
            .find(id, imageId)
            .orElseThrow(() -> WorkAccessService.missing("Evidence image not found."));
    if ((e.visibility() == WorkVisibility.INTERNAL && party != WorkParty.COORDINATOR)
        || (party == WorkParty.PROVIDER
            && e.assignmentId() != null
            && !e.assignmentId().equals(a.work().assignmentId())))
      throw WorkAccessService.missing("Evidence image not found.");
    try {
      return new ImageContent(e.id(), storage.read(e.storageKey()), e.contentType(), e.byteSize());
    } catch (IOException ex) {
      throw WorkAccessService.missing("Evidence content is unavailable.");
    }
  }

  private String hash(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
