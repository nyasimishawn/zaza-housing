package com.zaza.housing.service;

import com.zaza.housing.dto.InspectionEvidenceResponse;
import com.zaza.housing.dto.InspectionResponse;
import com.zaza.housing.enums.InspectionStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ImageContent;
import com.zaza.housing.model.InspectionEvidence;
import com.zaza.housing.repository.InspectionEvidenceRepository;
import com.zaza.housing.repository.InspectionRepository;
import com.zaza.housing.repository.LocalImageStorageRepository;
import com.zaza.housing.util.ImageProcessor;
import com.zaza.housing.util.Pagination;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class InspectionEvidenceService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(InspectionEvidenceService.class);
  private final InspectionAccessService access;
  private final InspectionRepository inspections;
  private final InspectionEvidenceRepository evidence;
  private final InspectionActivityService activity;
  private final InspectionReadService reads;
  private final LocalImageStorageRepository storage;
  private final AuditService audit;
  private final Clock clock;

  public InspectionEvidenceService(
      InspectionAccessService access,
      InspectionRepository inspections,
      InspectionEvidenceRepository evidence,
      InspectionActivityService activity,
      InspectionReadService reads,
      LocalImageStorageRepository storage,
      AuditService audit,
      Clock clock) {
    this.access = access;
    this.inspections = inspections;
    this.evidence = evidence;
    this.activity = activity;
    this.reads = reads;
    this.storage = storage;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional
  public InspectionResponse upload(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID leaseId,
      UUID id,
      UUID requestId,
      long version,
      String caption,
      MultipartFile file) {
    var lease = access.operationalLease(actor, org, property, leaseId, true);
    if (org != null) access.manager(actor, org, lease);
    var report = access.report(lease, id, org == null);
    if (version < 0 || caption == null || caption.isBlank() || caption.length() > 200)
      throw WorkAccessService.invalid(
          "Use a nonnegative version and a caption of 1-200 characters.");
    var image = ImageProcessor.process(file);
    var body =
        Map.of(
            "inspectionId",
            id,
            "requestId",
            requestId,
            "version",
            version,
            "caption",
            caption.strip(),
            "imageHash",
            hash(image.bytes()));
    if (activity.replay(actor, leaseId, requestId, "EVIDENCE_ADDED", body).isPresent())
      return reads.response(report, org == null);
    access.version(report, version);
    if (lease.unitReleasedAt() != null || report.status() == InspectionStatus.ACCEPTED)
      throw LeaseAccessService.conflict(
          "Evidence cannot be appended to an accepted inspection or released unit.");
    if (org != null) access.editable(report);
    else if (report.status() != InspectionStatus.AWAITING_TENANT
        && report.status() != InspectionStatus.DISPUTED)
      throw LeaseAccessService.conflict(
          "Tenant evidence can be added while reviewing or disputing a shared inspection.");
    if (evidence.count(id) >= 50)
      throw LeaseAccessService.conflict("An inspection can hold at most 50 evidence images.");
    final String key;
    try {
      key = storage.save(image);
    } catch (IOException e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "Inspection evidence storage is unavailable.");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED)
              try {
                storage.removeUncommitted(key);
              } catch (IOException e) {
                log.warn("Could not remove uncommitted inspection evidence: {}", key);
              }
          }
        });
    UUID imageId =
        evidence.create(
            id,
            actor.id(),
            org == null ? "TENANT" : "LESSOR",
            caption.strip(),
            key,
            image,
            clock.instant());
    inspections.touch(id, clock.instant());
    activity.finish(
        actor,
        org,
        lease,
        requestId,
        "EVIDENCE_ADDED",
        body,
        imageId,
        id,
        null,
        "An inspection evidence image was added.",
        List.of(),
        org == null,
        org == null);
    return reads.response(access.report(lease, id, org == null), org == null);
  }

  public List<InspectionEvidenceResponse> list(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID leaseId,
      UUID id,
      int limit,
      int offset) {
    var lease = access.operationalLease(actor, org, property, leaseId, false);
    access.report(lease, id, org == null);
    Pagination.validate(limit, offset);
    return evidence.list(id, org == null, limit, offset).stream().map(this::response).toList();
  }

  @Transactional
  public ImageContent content(
      AuthenticatedUser actor, UUID org, UUID property, UUID leaseId, UUID id, UUID image) {
    var lease = access.operationalLease(actor, org, property, leaseId, true);
    access.report(lease, id, org == null);
    var e =
        evidence
            .find(id, image, org == null)
            .orElseThrow(() -> WorkAccessService.missing("Inspection evidence not found."));
    try {
      var content =
          new ImageContent(e.id(), storage.read(e.storageKey()), e.contentType(), e.byteSize());
      audit.record(actor.id(), org, "INSPECTION_EVIDENCE_READ", image);
      return content;
    } catch (IOException error) {
      throw WorkAccessService.missing("Inspection evidence content is unavailable.");
    }
  }

  private InspectionEvidenceResponse response(InspectionEvidence e) {
    return new InspectionEvidenceResponse(
        e.id(),
        e.party(),
        e.caption(),
        e.contentType(),
        e.width(),
        e.height(),
        e.byteSize(),
        e.createdAt());
  }

  private String hash(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException(error);
    }
  }
}
