package com.zaza.housing.controller;

import com.zaza.housing.dto.DocumentCommandRequest;
import com.zaza.housing.dto.DocumentEventResponse;
import com.zaza.housing.dto.DocumentResponse;
import com.zaza.housing.dto.DocumentRetentionRequest;
import com.zaza.housing.dto.DocumentStorageStatusResponse;
import com.zaza.housing.dto.DocumentUploadMetadata;
import com.zaza.housing.dto.DocumentVersionResponse;
import com.zaza.housing.dto.PurgeDocumentRequest;
import com.zaza.housing.enums.DocumentStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.DocumentReadService;
import com.zaza.housing.service.DocumentService;
import com.zaza.housing.util.DocumentHttpResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping({"/api/v1/me/documents", "/api/v1/organizations/{organizationId}/documents"})
public class DocumentController {
  private final DocumentService documents;
  private final DocumentReadService reads;

  public DocumentController(DocumentService documents, DocumentReadService reads) {
    this.documents = documents;
    this.reads = reads;
  }

  @GetMapping("/storage-status")
  public DocumentStorageStatusResponse storageStatus(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId) {
    return reads.status(actor, organizationId);
  }

  @GetMapping
  public List<DocumentResponse> list(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @RequestParam(required = false) DocumentStatus status,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) com.zaza.housing.enums.DocumentCategory category,
      @RequestParam(required = false) String kind,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.search(actor, organizationId, status, q, category, kind, limit, offset);
  }

  @GetMapping("/{id}")
  public DocumentResponse get(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id) {
    return reads.get(actor, organizationId, id);
  }

  @GetMapping("/{id}/versions")
  public List<DocumentVersionResponse> versions(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.versions(actor, organizationId, id, limit, offset);
  }

  @GetMapping("/{id}/history")
  public List<DocumentEventResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.history(actor, organizationId, id, limit, offset);
  }

  @GetMapping("/{id}/versions/{versionId}/content")
  public ResponseEntity<Resource> content(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @PathVariable UUID versionId) {
    return DocumentHttpResponse.content(reads.content(actor, organizationId, id, versionId));
  }

  @PostMapping(value = "", consumes = "multipart/form-data")
  @ResponseStatus(HttpStatus.CREATED)
  public DocumentResponse upload(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @Valid @RequestPart("metadata") DocumentUploadMetadata metadata,
      @RequestPart("file") MultipartFile file) {
    return documents.upload(actor, organizationId, null, metadata, file);
  }

  @PostMapping(value = "/{id}/versions", consumes = "multipart/form-data")
  public DocumentResponse uploadVersion(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @Valid @RequestPart("metadata") DocumentUploadMetadata metadata,
      @RequestPart("file") MultipartFile file) {
    return documents.upload(actor, organizationId, id, metadata, file);
  }

  @PostMapping("/{id}/archive")
  public DocumentResponse archive(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @Valid @RequestBody DocumentCommandRequest request) {
    return documents.archive(actor, organizationId, id, request, false);
  }

  @PostMapping("/{id}/restore")
  public DocumentResponse restore(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @Valid @RequestBody DocumentCommandRequest request) {
    return documents.archive(actor, organizationId, id, request, true);
  }

  @PostMapping("/{id}/retention")
  public DocumentResponse retention(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @Valid @RequestBody DocumentRetentionRequest request) {
    return documents.retain(actor, organizationId, id, request);
  }

  @PostMapping("/{id}/purge")
  public DocumentResponse purge(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @Valid @RequestBody PurgeDocumentRequest request) {
    return documents.requestPurge(actor, organizationId, id, request);
  }

  @PostMapping("/{id}/purge/process")
  public DocumentResponse purge_process(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @Valid @RequestBody DocumentCommandRequest request) {
    return documents.processPurge(actor, organizationId, id, request);
  }
}
