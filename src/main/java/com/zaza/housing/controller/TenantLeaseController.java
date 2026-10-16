package com.zaza.housing.controller;

import com.zaza.housing.dto.BillingCurrencySummaryResponse;
import com.zaza.housing.dto.ConfirmLeaseTerminationRequest;
import com.zaza.housing.dto.ConversationResponse;
import com.zaza.housing.dto.CreateLeaseTerminationRequest;
import com.zaza.housing.dto.DisputeInspectionRequest;
import com.zaza.housing.dto.InspectionAttestationResponse;
import com.zaza.housing.dto.InspectionComparisonResponse;
import com.zaza.housing.dto.InspectionEventResponse;
import com.zaza.housing.dto.InspectionEvidenceResponse;
import com.zaza.housing.dto.InspectionResponse;
import com.zaza.housing.dto.InspectionRevisionResponse;
import com.zaza.housing.dto.LeaseCheckoutResponse;
import com.zaza.housing.dto.LeaseDashboardResponse;
import com.zaza.housing.dto.LeaseEventResponse;
import com.zaza.housing.dto.LeaseReasonRequest;
import com.zaza.housing.dto.LeaseResponse;
import com.zaza.housing.dto.LeaseRevisionResponse;
import com.zaza.housing.dto.LeaseSigningReceipt;
import com.zaza.housing.dto.LeaseTerminationPreviewRequest;
import com.zaza.housing.dto.LeaseTerminationPreviewResponse;
import com.zaza.housing.dto.LeaseTerminationResponse;
import com.zaza.housing.dto.OpenConversationRequest;
import com.zaza.housing.dto.ReviewInspectionRequest;
import com.zaza.housing.dto.SignLeaseRequest;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.BillingReportService;
import com.zaza.housing.service.ConversationService;
import com.zaza.housing.service.InspectionEvidenceService;
import com.zaza.housing.service.InspectionReadService;
import com.zaza.housing.service.InspectionService;
import com.zaza.housing.service.LeaseReadService;
import com.zaza.housing.service.LeaseService;
import com.zaza.housing.service.LeaseTerminationService;
import com.zaza.housing.util.AgreementHttpResponse;
import com.zaza.housing.util.ImageHttpResponse;
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
@RequestMapping("/api/v1/me/leases")
public class TenantLeaseController {
  private final InspectionService inspections;
  private final InspectionReadService inspectionReads;
  private final InspectionEvidenceService inspectionEvidence;
  private final ConversationService conversations;
  private final BillingReportService billingReports;
  private final LeaseService leases;
  private final LeaseReadService reads;
  private final LeaseTerminationService endings;

  public TenantLeaseController(
      LeaseService leases,
      LeaseReadService reads,
      LeaseTerminationService endings,
      BillingReportService billingReports,
      ConversationService conversations,
      InspectionService inspections,
      InspectionReadService inspectionReads,
      InspectionEvidenceService inspectionEvidence) {
    this.inspections = inspections;
    this.inspectionReads = inspectionReads;
    this.inspectionEvidence = inspectionEvidence;
    this.conversations = conversations;
    this.leases = leases;
    this.reads = reads;
    this.endings = endings;
    this.billingReports = billingReports;
  }

  @GetMapping
  public List<LeaseResponse> list(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(required = false) LeaseStatus status,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.list(actor, null, null, status, limit, offset);
  }

  @GetMapping("/summary")
  public LeaseDashboardResponse summary(@AuthenticationPrincipal AuthenticatedUser actor) {
    return reads.summary(actor, null, null);
  }

  @GetMapping("/billing-summary")
  public List<BillingCurrencySummaryResponse> billingSummary(
      @AuthenticationPrincipal AuthenticatedUser actor) {
    return billingReports.summary(actor, null, null);
  }

  @GetMapping("/{id}")
  public LeaseResponse get(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return reads.tenant(actor, id);
  }

  @PostMapping("/{id}/decline")
  public LeaseResponse decline(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody LeaseReasonRequest request) {
    return leases.decline(actor, id, request);
  }

  @PostMapping("/{id}/sign")
  public LeaseResponse sign(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody SignLeaseRequest request) {
    return leases.sign(actor, null, null, id, request);
  }

  @GetMapping("/{id}/history")
  public List<LeaseEventResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.history(actor, null, null, id, limit, offset);
  }

  @GetMapping("/{id}/revisions")
  public List<LeaseRevisionResponse> revisions(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.revisions(actor, null, null, id, limit, offset);
  }

  @GetMapping("/{id}/revisions/{revisionId}/document")
  public ResponseEntity<String> document(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID revisionId) {
    var revision = reads.revision(actor, null, null, id, revisionId);
    return AgreementHttpResponse.document(
        revision.document(), "lease-" + id + "-r" + revision.number() + ".txt");
  }

  @GetMapping("/{id}/revisions/{revisionId}/receipt")
  public LeaseSigningReceipt receipt(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID revisionId) {
    return reads.receipt(actor, null, null, id, revisionId);
  }

  @GetMapping("/{id}/terminations")
  public List<LeaseTerminationResponse> terminations(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.terminations(actor, null, null, id, limit, offset);
  }

  @PostMapping("/{id}/terminations/preview")
  public LeaseTerminationPreviewResponse previewTermination(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody LeaseTerminationPreviewRequest request) {
    return endings.preview(actor, null, null, id, request);
  }

  @PostMapping("/{id}/terminations")
  @ResponseStatus(HttpStatus.CREATED)
  public LeaseResponse requestTermination(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody CreateLeaseTerminationRequest request) {
    return endings.request(actor, null, null, id, request);
  }

  @GetMapping("/{id}/terminations/{terminationId}/document")
  public ResponseEntity<String> terminationDocument(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID terminationId) {
    var ending = reads.termination(actor, null, null, id, terminationId);
    return AgreementHttpResponse.document(
        ending.document(), "termination-" + terminationId + ".txt");
  }

  @PostMapping("/{id}/terminations/{terminationId}/accept")
  public LeaseResponse acceptTermination(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID terminationId,
      @Valid @RequestBody ConfirmLeaseTerminationRequest request) {
    return endings.accept(actor, null, null, id, terminationId, request);
  }

  @PostMapping("/{id}/terminations/{terminationId}/reject")
  public LeaseResponse rejectTermination(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID terminationId,
      @Valid @RequestBody LeaseReasonRequest request) {
    return endings.resolve(actor, null, null, id, terminationId, request, false);
  }

  @PostMapping("/{id}/terminations/{terminationId}/cancel")
  public LeaseResponse cancelTermination(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID terminationId,
      @Valid @RequestBody LeaseReasonRequest request) {
    return endings.resolve(actor, null, null, id, terminationId, request, true);
  }

  @PostMapping("/{id}/conversations")
  public ConversationResponse conversation(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody OpenConversationRequest request) {
    return conversations.lease(actor, null, null, id, request);
  }

  @GetMapping("/{id}/checkout")
  public LeaseCheckoutResponse checkout(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return inspectionReads.checkout(actor, null, null, id);
  }

  @GetMapping("/{id}/inspections")
  public List<InspectionResponse> inspections(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return inspectionReads.list(actor, null, null, id);
  }

  @GetMapping("/{id}/inspections/comparison")
  public InspectionComparisonResponse inspectionComparison(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return inspectionReads.comparison(actor, null, null, id);
  }

  @GetMapping("/{id}/inspections/{inspectionId}")
  public InspectionResponse inspection(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId) {
    return inspectionReads.get(actor, null, null, id, inspectionId);
  }

  @GetMapping("/{id}/inspections/{inspectionId}/revisions")
  public List<InspectionRevisionResponse> inspectionRevisions(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return inspectionReads.revisions(actor, null, null, id, inspectionId, limit, offset);
  }

  @GetMapping("/{id}/inspections/{inspectionId}/history")
  public List<InspectionEventResponse> inspectionHistory(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return inspectionReads.history(actor, null, null, id, inspectionId, limit, offset);
  }

  @GetMapping("/{id}/inspections/{inspectionId}/revisions/{revisionId}/document")
  public ResponseEntity<String> inspectionDocument(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId,
      @PathVariable UUID revisionId) {
    var revision = inspectionReads.document(actor, null, null, id, inspectionId, revisionId);
    return AgreementHttpResponse.document(
        revision.document(), "inspection-" + inspectionId + "-r" + revision.number() + ".json");
  }

  @GetMapping("/{id}/inspections/{inspectionId}/evidence")
  public List<InspectionEvidenceResponse> inspectionEvidence(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return inspectionEvidence.list(actor, null, null, id, inspectionId, limit, offset);
  }

  @GetMapping("/{id}/inspections/{inspectionId}/evidence/{imageId}/content")
  public ResponseEntity<Resource> inspectionImage(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId,
      @PathVariable UUID imageId) {
    return ImageHttpResponse.content(
        inspectionEvidence.content(actor, null, null, id, inspectionId, imageId));
  }

  @PostMapping(
      value = "/{id}/inspections/{inspectionId}/evidence",
      consumes = "multipart/form-data")
  public InspectionResponse uploadInspectionImage(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId,
      @RequestParam UUID requestId,
      @RequestParam long version,
      @RequestParam String caption,
      @RequestPart("file") MultipartFile file) {
    return inspectionEvidence.upload(
        actor, null, null, id, inspectionId, requestId, version, caption, file);
  }

  @PostMapping("/{id}/inspections/{inspectionId}/accept")
  public InspectionResponse acceptInspection(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId,
      @Valid @RequestBody ReviewInspectionRequest request) {
    return inspections.accept(actor, id, inspectionId, request);
  }

  @PostMapping("/{id}/inspections/{inspectionId}/dispute")
  public InspectionResponse disputeInspection(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId,
      @Valid @RequestBody DisputeInspectionRequest request) {
    return inspections.dispute(actor, id, inspectionId, request);
  }

  @GetMapping("/{id}/inspections/{inspectionId}/attestations")
  public List<InspectionAttestationResponse> inspectionAttestations(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID inspectionId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return inspectionReads.attestations(actor, null, null, id, inspectionId, limit, offset);
  }
}
