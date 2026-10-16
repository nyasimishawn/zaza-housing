package com.zaza.housing.controller;

import com.zaza.housing.dto.ApplicantApplicationResponse;
import com.zaza.housing.dto.ApplicationDashboardResponse;
import com.zaza.housing.dto.ApplicationDocumentResponse;
import com.zaza.housing.dto.ApplicationEventResponse;
import com.zaza.housing.dto.ApplicationListingResponse;
import com.zaza.housing.dto.ApplicationVersionRequest;
import com.zaza.housing.dto.CreateApplicationRequest;
import com.zaza.housing.dto.RefreshApplicationListingRequest;
import com.zaza.housing.dto.RevokeDocumentGrantRequest;
import com.zaza.housing.dto.ShareApplicationDocumentRequest;
import com.zaza.housing.dto.SubmitApplicationRequest;
import com.zaza.housing.dto.UpdateApplicationRequest;
import com.zaza.housing.enums.ApplicationStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.ApplicationDocumentService;
import com.zaza.housing.service.RentalApplicationService;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class TenantApplicationController {
  private final ApplicationDocumentService documents;
  private final RentalApplicationService applications;

  public TenantApplicationController(
      RentalApplicationService applications, ApplicationDocumentService documents) {
    this.applications = applications;
    this.documents = documents;
  }

  @GetMapping("/application-listings/{listingId}")
  public ApplicationListingResponse preview(@PathVariable UUID listingId) {
    return applications.preview(listingId);
  }

  @PostMapping("/applications")
  @ResponseStatus(HttpStatus.CREATED)
  public ApplicantApplicationResponse create(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @Valid @RequestBody CreateApplicationRequest request) {
    return applications.create(actor, request);
  }

  @GetMapping("/applications")
  public List<ApplicantApplicationResponse> list(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(required = false) ApplicationStatus status,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return applications.listOwn(actor, status, limit, offset);
  }

  @GetMapping("/applications/summary")
  public ApplicationDashboardResponse summary(@AuthenticationPrincipal AuthenticatedUser actor) {
    return applications.ownDashboard(actor);
  }

  @GetMapping("/applications/{id}")
  public ApplicantApplicationResponse get(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return applications.getOwn(actor, id);
  }

  @PutMapping("/applications/{id}")
  public ApplicantApplicationResponse update(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody UpdateApplicationRequest request) {
    return applications.update(actor, id, request);
  }

  @PostMapping("/applications/{id}/refresh-listing")
  public ApplicantApplicationResponse refresh(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody RefreshApplicationListingRequest request) {
    return applications.refreshListing(actor, id, request);
  }

  @PostMapping("/applications/{id}/submit")
  public ApplicantApplicationResponse submit(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody SubmitApplicationRequest request) {
    return applications.submit(actor, id, request);
  }

  @PostMapping("/applications/{id}/withdraw")
  public ApplicantApplicationResponse withdraw(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ApplicationVersionRequest request) {
    return applications.withdraw(actor, id, request);
  }

  @GetMapping("/applications/{id}/history")
  public List<ApplicationEventResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return applications.ownHistory(actor, id, limit, offset);
  }

  @GetMapping("/applications/{id}/documents")
  public List<ApplicationDocumentResponse> documents(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return documents.list(actor, null, null, id, limit, offset);
  }

  @GetMapping("/applications/{id}/documents/{grantId}/content")
  public ResponseEntity<Resource> documentContent(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID grantId) {
    return DocumentHttpResponse.content(documents.content(actor, null, null, id, grantId));
  }

  @PostMapping("/applications/{id}/documents")
  @ResponseStatus(HttpStatus.CREATED)
  public ApplicationDocumentResponse shareDocument(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ShareApplicationDocumentRequest request) {
    return documents.share(actor, id, request);
  }

  @PostMapping("/applications/{id}/documents/{grantId}/revoke")
  public ApplicationDocumentResponse revokeDocument(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID grantId,
      @Valid @RequestBody RevokeDocumentGrantRequest request) {
    return documents.revoke(actor, id, grantId, request);
  }
}
