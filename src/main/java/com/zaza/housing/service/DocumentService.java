package com.zaza.housing.service;

import com.zaza.housing.dto.DocumentCommandRequest;
import com.zaza.housing.dto.DocumentResponse;
import com.zaza.housing.dto.DocumentRetentionRequest;
import com.zaza.housing.dto.DocumentUploadMetadata;
import com.zaza.housing.dto.PurgeDocumentRequest;
import com.zaza.housing.enums.DocumentStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.VaultDocument;
import com.zaza.housing.model.ProcessedDocument;
import com.zaza.housing.repository.DocumentRepository;
import com.zaza.housing.repository.LocalDocumentStorageRepository;
import com.zaza.housing.util.DocumentCryptography;
import com.zaza.housing.util.ImageProcessor;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentService {
  private static final org.slf4j.Logger log =
      org.slf4j.LoggerFactory.getLogger(DocumentService.class);
  private final DocumentAccessService access;
  private final DocumentRepository documents;
  private final DocumentReadService reads;
  private final DocumentActivityService activity;
  private final LocalDocumentStorageRepository storage;
  private final DocumentCryptography crypto;
  private final AuthService auth;
  private final com.zaza.housing.repository.AuthRepository users;
  private final Clock clock;
  private final int uploadsPerHour;
  private final DocumentProcessingService processing;
  private final TransactionTemplate transactions;

  public DocumentService(
      DocumentAccessService access,
      DocumentRepository documents,
      DocumentReadService reads,
      DocumentActivityService activity,
      LocalDocumentStorageRepository storage,
      DocumentCryptography crypto,
      AuthService auth,
      com.zaza.housing.repository.AuthRepository users,
      Clock clock,
      DocumentProcessingService processing,
      PlatformTransactionManager manager,
      @Value("${zaza.documents.uploads-per-hour:20}") int uploadsPerHour) {
    this.access = access;
    this.documents = documents;
    this.reads = reads;
    this.activity = activity;
    this.storage = storage;
    this.crypto = crypto;
    this.auth = auth;
    this.users = users;
    this.clock = clock;
    this.processing = processing;
    this.transactions = new TransactionTemplate(manager);
    if (uploadsPerHour < 1 || uploadsPerHour > 1000)
      throw new IllegalArgumentException("Document upload quota must be between 1 and 1000.");
    this.uploadsPerHour = uploadsPerHour;
  }

  public DocumentResponse upload(
      AuthenticatedUser actor,
      UUID org,
      UUID id,
      DocumentUploadMetadata request,
      MultipartFile file) {
    transactions.executeWithoutResult(status -> {
      if(users.findById(actor.id()).isEmpty())throw new ApiException(HttpStatus.FORBIDDEN,"The document owner is no longer active.");
    access.owner(actor, org, true);
      crypto.requireConfigured();
      if (id != null) {
        var existing = access.own(actor, org, id, false);
        access.active(existing);
      }
    });
    // Untrusted PDF decoding never holds a database connection or subject lock.
    var processed = processing.process(file);
    return transactions.execute(status -> persistUpload(actor, org, id, request, processed));
  }

  private DocumentResponse persistUpload(AuthenticatedUser actor, UUID org, UUID id,
      DocumentUploadMetadata request, ProcessedDocument processed) {
    if(users.findById(actor.id()).isEmpty())throw new ApiException(HttpStatus.FORBIDDEN,"The document owner is no longer active.");
    access.owner(actor, org, true);
    crypto.requireConfigured();
    var image = processed.content();
    String hash = DocumentCryptography.hash(image.bytes());
    String scope = DocumentAccessService.scope(org == null ? actor.id() : null, org);
    String action = id == null ? "DOCUMENT_UPLOADED" : "DOCUMENT_VERSION_ADDED";
    var body =
        Map.of(
            "documentId",
            id == null ? "NEW" : id.toString(),
            "request",
            request,
            "contentHash",
            processed.requestHash());
    var replay = activity.replay(actor, scope, request.requestId(), action, body);
    if (replay.isPresent()) return reads.response(access.own(actor, org, replay.get(), false));
    if (documents.uploads(scope, clock.instant().minus(Duration.ofHours(1))) >= uploadsPerHour)
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS, "Document upload quota exceeded. Retry later.");
    UUID document = id == null ? UUID.randomUUID() : id;
    UUID version = UUID.randomUUID();
    int number = 1;
    VaultDocument previous = null;
    if (id == null) {
      if (request.version() != 0)
        throw DocumentAccessService.invalid("A new document starts at version zero.");
      retention(request.retainUntil());
      if (documents.activeCount(actor.id(), org) >= 100)
        throw DocumentAccessService.conflict(
            "A document owner can hold at most 100 documents that have not been purged.");
    } else {
      previous = access.own(actor, org, id, false);
      access.version(previous, request.version());
      access.active(previous);
      if (previous.category() != request.category()
          || !previous.title().equals(request.title().strip())
          || !previous.retainUntil().equals(request.retainUntil()))
        throw DocumentAccessService.conflict(
            "Use the existing document identity and retention when uploading a replacement"
                + " version.");
      number = access.version(previous, previous.currentVersionId()).number() + 1;
      if (number > 20)
        throw DocumentAccessService.conflict("A document can hold at most 20 immutable versions.");
    }
    var encrypted = crypto.encrypt(document, version, image.contentType(), hash, image.bytes());
    String key;
    try {
      key = storage.save(encrypted);
    } catch (IOException e) {
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE, "Encrypted document storage is unavailable.");
    }
    final String committedKey = key;
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED)
              try {
                storage.remove(committedKey);
              } catch (IOException e) {
                log.warn("Could not remove uncommitted encrypted document: {}", committedKey);
              }
          }
        });
    if (id == null)
      documents.create(
          document,
          org == null ? actor.id() : null,
          org,
          request.category(),
          request.title().strip(),
          version,
          request.retainUntil(),
          actor.id(),
          clock.instant());
    documents.version(
        version,
        document,
        number,
        key,
        encrypted.keyId(),
        image,
        processed.pageCount(),
        hash,
        encrypted.bytes().length,
        actor.id(),
        clock.instant());
    if (id != null) documents.current(document, version, clock.instant());
    var current = access.find(document);
    activity.document(
        actor,
        current,
        request.requestId(),
        action,
        body,
        document,
        "An encrypted document version was stored.");
    return reads.response(current);
  }

  @Transactional
  public DocumentResponse archive(
      AuthenticatedUser actor, UUID org, UUID id, DocumentCommandRequest request, boolean restore) {
    var d = access.own(actor, org, id, true);
    String action = restore ? "DOCUMENT_RESTORED" : "DOCUMENT_ARCHIVED";
    var body = Map.of("documentId", id, "request", request);
    if (activity
        .replay(
            actor,
            DocumentAccessService.scope(d.ownerUserId(), org),
            request.requestId(),
            action,
            body)
        .isPresent()) return reads.response(d);
    access.version(d, request.version());
    if (restore) {
      if (d.status() != DocumentStatus.ARCHIVED)
        throw DocumentAccessService.conflict("Only an archived document can be restored.");
    } else {
      access.active(d);
      if (documents.held(id, clock.instant()))
        throw DocumentAccessService.conflict(
            "Withdraw or resolve open/valid verification cases before archiving their evidence.");
      documents.revokeAll(id, clock.instant());
    }
    documents.state(id, restore ? DocumentStatus.ACTIVE : DocumentStatus.ARCHIVED, clock.instant());
    var current = access.find(id);
    activity.document(
        actor, current, request.requestId(), action, body, id, request.reason().strip());
    return reads.response(current);
  }

  @Transactional
  public DocumentResponse retain(
      AuthenticatedUser actor, UUID org, UUID id, DocumentRetentionRequest request) {
    var d = access.own(actor, org, id, true);
    var body = Map.of("documentId", id, "request", request);
    String scope = DocumentAccessService.scope(d.ownerUserId(), org);
    if (activity.replay(actor, scope, request.requestId(), "RETENTION_EXTENDED", body).isPresent())
      return reads.response(d);
    access.version(d, request.version());
    if (d.status() == DocumentStatus.PURGE_PENDING || d.status() == DocumentStatus.PURGED)
      throw DocumentAccessService.conflict("Purge cannot be undone by extending retention.");
    retention(request.retainUntil());
    if (!request.retainUntil().isAfter(d.retainUntil()))
      throw DocumentAccessService.invalid("Retention can only be extended.");
    documents.retention(id, request.retainUntil(), clock.instant());
    var current = access.find(id);
    activity.document(
        actor,
        current,
        request.requestId(),
        "RETENTION_EXTENDED",
        body,
        id,
        request.reason().strip());
    return reads.response(current);
  }

  @Transactional
  public DocumentResponse requestPurge(
      AuthenticatedUser actor, UUID org, UUID id, PurgeDocumentRequest request) {
    var d = access.own(actor, org, id, true);
    auth.reauthenticate(actor, request.password());
    var body = Map.of("documentId", id, "request", request);
    String scope = DocumentAccessService.scope(d.ownerUserId(), org);
    if (activity.replay(actor, scope, request.requestId(), "PURGE_REQUESTED", body).isPresent())
      return reads.response(d);
    access.version(d, request.version());
    if (!Boolean.TRUE.equals(request.consent()))
      throw DocumentAccessService.invalid(
          "Explicitly confirm irreversible deletion of every document version.");
    if (d.status() != DocumentStatus.ARCHIVED
        || clock.instant().isBefore(d.retainUntil())
        || documents.held(id, clock.instant()))
      throw DocumentAccessService.conflict(
          "Archive the document, wait for its retention deadline and resolve verification holds"
              + " before requesting purge.");
    documents.queuePurge(id);
    documents.state(id, DocumentStatus.PURGE_PENDING, clock.instant());
    var current = access.find(id);
    activity.document(
        actor, current, request.requestId(), "PURGE_REQUESTED", body, id, request.reason().strip());
    return reads.response(current);
  }

  @Transactional
  public DocumentResponse processPurge(
      AuthenticatedUser actor, UUID org, UUID id, DocumentCommandRequest request) {
    var d = access.own(actor, org, id, true);
    var body = Map.of("documentId", id, "request", request);
    String scope = DocumentAccessService.scope(d.ownerUserId(), org);
    if (activity.replay(actor, scope, request.requestId(), "PURGE_PROCESSED", body).isPresent())
      return reads.response(d);
    access.version(d, request.version());
    if (d.status() != DocumentStatus.PURGE_PENDING)
      throw DocumentAccessService.conflict(
          "Only a committed pending purge may remove encrypted files.");
    for (var version : documents.purgeTasks(id)) {
      try {
        storage.remove(version.storageKey());
      } catch (IOException e) {
        throw new ApiException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Purge remains pending; retry to remove remaining encrypted files.");
      }
      documents.purged(id, version.id(), clock.instant());
    }
    documents.state(id, DocumentStatus.PURGED, clock.instant());
    var current = access.find(id);
    activity.document(
        actor, current, request.requestId(), "PURGE_PROCESSED", body, id, request.reason().strip());
    return reads.response(current);
  }

  private void retention(java.time.Instant until) {
    if (until.isBefore(clock.instant().plus(Duration.ofDays(1)))
        || until.isAfter(clock.instant().plus(Duration.ofDays(3650))))
      throw DocumentAccessService.invalid("Set retention between one day and ten years from now.");
  }
}
