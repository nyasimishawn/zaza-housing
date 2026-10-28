package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.*;
import com.zaza.housing.repository.*;
import com.zaza.housing.util.*;
import java.io.IOException;
import java.time.Clock;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SupportEvidenceService {
  private final SupportRepository repository;
  private final SupportAccessService access;
  private final SupportCaseService cases;
  private final AdministrationActivityService activity;
  private final SecurityCryptography crypto;
  private final LocalDocumentStorageRepository storage;
  private final Clock clock;

  public SupportEvidenceService(
      SupportRepository repository,
      SupportAccessService access,
      SupportCaseService cases,
      AdministrationActivityService activity,
      SecurityCryptography crypto,
      LocalDocumentStorageRepository storage,
      Clock clock) {
    this.repository = repository;
    this.access = access;
    this.cases = cases;
    this.activity = activity;
    this.crypto = crypto;
    this.storage = storage;
    this.clock = clock;
  }

  private String base(boolean admin) {
    return admin ? "/api/v1/admin/operations/support-cases" : "/api/v1/me/support/cases";
  }

  public List<SupportEvidenceResponse> list(
      AuthenticatedUser a, String token, UUID id, boolean admin) {
    var c = access.get(a, token, id, admin, false);
    if (admin) access.assigned(a, c);
    return repository.evidence(id, admin, base(admin));
  }

  @Transactional
  public SupportCaseResponse upload(
      AuthenticatedUser a,
      String token,
      UUID caseId,
      boolean admin,
      UUID request,
      long version,
      String caption,
      boolean internal,
      MultipartFile file) {
    var c = access.get(a, token, caseId, admin, true);
    if (admin) access.assigned(a, c);
    else if (internal)
      throw WorkAccessService.forbidden("Only the assigned operator can add internal evidence.");
    if (version < 0 || caption == null || caption.isBlank() || caption.strip().length() > 200)
      throw AgencyAccessService.bad(
          "Use the current case version and a caption of 1–200 characters.");
    var image = ImageProcessor.processPrivate(file);
    String hash = DocumentCryptography.hash(image.bytes());
    var body =
        Map.of(
            "requestId",
            request,
            "version",
            version,
            "caption",
            caption.strip(),
            "internal",
            internal,
            "sha256",
            hash);
    if (activity.replay(a, request, "SUPPORT_EVIDENCE_ADDED", caseId, body) != null)
      return cases.get(a, token, caseId, admin);
    AdministrationAccessService.version(c.version(), version);
    access.open(c);
    if (repository.evidenceCount(caseId) >= 20)
      throw AgencyAccessService.conflict("A case can hold at most 20 evidence images.");
    UUID id = UUID.randomUUID();
    String envelope = crypto.encrypt("SUPPORT_EVIDENCE|" + caseId + "|" + id, image.bytes());
    var parts = envelope.split("\\.", 2);
    byte[] cipher = Base64.getDecoder().decode(parts[1]);
    final String key;
    try {
      key = storage.save(new EncryptedDocument(cipher, parts[0]));
    } catch (IOException e) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Evidence storage is unavailable.");
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED)
              try {
                storage.remove(key);
              } catch (IOException e) {
                org.slf4j.LoggerFactory.getLogger(SupportEvidenceService.class)
                    .warn("Could not remove uncommitted support evidence {}", key);
              }
          }
        });
    repository.evidence(
        id,
        caseId,
        a.id(),
        caption.strip(),
        internal,
        key,
        parts[0],
        parts[0] + ".",
        image,
        cipher.length,
        hash,
        clock.instant());
    repository.touch(caseId, false, clock.instant());
    activity.record(
        a,
        request,
        "SUPPORT_EVIDENCE_ADDED",
        "SUPPORT_CASE",
        caseId,
        body,
        "Evidence image added.",
        internal);
    if (!internal)
      repository.notify(
          admin ? c.reporterId() : c.assignedTo(),
          caseId,
          "Evidence was added to a support case.",
          clock.instant());
    return cases.get(a, token, caseId, admin);
  }

  public ImageContent content(
      AuthenticatedUser a, String token, UUID caseId, UUID id, boolean admin) {
    var c = access.get(a, token, caseId, admin, false);
    if (admin) access.assigned(a, c);
    var e = repository.evidence(caseId, id);
    if (e == null || (!admin && Boolean.TRUE.equals(e.get("internal"))))
      throw CaretakerAccessService.missing();
    try {
      byte[] cipher =
          storage.read((String) e.get("storage_key"), ((Number) e.get("cipher_size")).longValue());
      byte[] bytes =
          crypto.decrypt(
              "SUPPORT_EVIDENCE|" + caseId + "|" + id,
              (String) e.get("envelope_prefix") + Base64.getEncoder().encodeToString(cipher));
      if (bytes.length != ((Number) e.get("byte_size")).longValue()
          || !DocumentCryptography.hash(bytes).equals(e.get("sha256"))) throw new IOException();
      return new ImageContent(
          id,
          new org.springframework.core.io.ByteArrayResource(bytes),
          (String) e.get("content_type"),
          bytes.length);
    } catch (IOException ex) {
      throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Evidence content is unavailable.");
    }
  }
}
