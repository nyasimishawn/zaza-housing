package com.zaza.housing.controller;

import com.zaza.housing.dto.DocumentPageResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.ApplicationDocumentService;
import com.zaza.housing.service.DocumentProcessingService;
import com.zaza.housing.service.DocumentReadService;
import com.zaza.housing.service.VerificationReadService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DocumentPageController {
  private final DocumentReadService documents;
  private final ApplicationDocumentService applications;
  private final VerificationReadService verifications;
  private final DocumentProcessingService processing;
  public DocumentPageController(DocumentReadService documents, ApplicationDocumentService applications,
      VerificationReadService verifications, DocumentProcessingService processing) {
    this.documents = documents; this.applications = applications;
    this.verifications = verifications; this.processing = processing;
  }

  @GetMapping({"/api/v1/me/documents/{id}/versions/{versionId}/pages/{page}",
      "/api/v1/organizations/{organizationId}/documents/{id}/versions/{versionId}/pages/{page}"})
  public ResponseEntity<DocumentPageResponse> own(@AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required=false) UUID organizationId, @PathVariable UUID id,
      @PathVariable UUID versionId, @PathVariable int page) {
    return response(processing.page(documents.content(actor, organizationId, id, versionId), page));
  }

  @GetMapping({"/api/v1/me/applications/{id}/documents/{grantId}/pages/{page}",
      "/api/v1/organizations/{organizationId}/properties/{propertyId}/applications/{id}/documents/{grantId}/pages/{page}"})
  public ResponseEntity<DocumentPageResponse> application(@AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required=false) UUID organizationId, @PathVariable(required=false) UUID propertyId,
      @PathVariable UUID id, @PathVariable UUID grantId, @PathVariable int page) {
    return response(processing.page(applications.content(actor, organizationId, propertyId, id, grantId), page));
  }

  @GetMapping("/api/v1/admin/verifications/{id}/evidence/{versionId}/pages/{page}")
  public ResponseEntity<DocumentPageResponse> review(@AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id, @PathVariable UUID versionId, @PathVariable int page) {
    return response(processing.page(verifications.content(actor, id, versionId), page));
  }

  private ResponseEntity<DocumentPageResponse> response(DocumentPageResponse page) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff").body(page);
  }
}
