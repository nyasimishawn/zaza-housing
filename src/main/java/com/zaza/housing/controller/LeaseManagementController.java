package com.zaza.housing.controller;

import com.zaza.housing.dto.ActivateLeaseRequest;
import com.zaza.housing.dto.BillingCurrencySummaryResponse;
import com.zaza.housing.dto.ConfirmLeaseTerminationRequest;
import com.zaza.housing.dto.ConversationResponse;
import com.zaza.housing.dto.CreateLeaseRequest;
import com.zaza.housing.dto.CreateLeaseTerminationRequest;
import com.zaza.housing.dto.LeaseDashboardResponse;
import com.zaza.housing.dto.LeaseEventResponse;
import com.zaza.housing.dto.LeaseReasonRequest;
import com.zaza.housing.dto.LeaseResponse;
import com.zaza.housing.dto.LeaseRevisionResponse;
import com.zaza.housing.dto.LeaseSigningReceipt;
import com.zaza.housing.dto.LeaseTerminationPreviewRequest;
import com.zaza.housing.dto.LeaseTerminationPreviewResponse;
import com.zaza.housing.dto.LeaseTerminationResponse;
import com.zaza.housing.dto.LeaseVersionRequest;
import com.zaza.housing.dto.OpenConversationRequest;
import com.zaza.housing.dto.ReleaseLeaseUnitRequest;
import com.zaza.housing.dto.ReviseLeaseRequest;
import com.zaza.housing.dto.SignLeaseRequest;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.BillingReportService;
import com.zaza.housing.service.ConversationService;
import com.zaza.housing.service.LeaseReadService;
import com.zaza.housing.service.LeaseService;
import com.zaza.housing.service.LeaseTerminationService;
import com.zaza.housing.util.AgreementHttpResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
@RequestMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/leases")
public class LeaseManagementController {
  private final ConversationService conversations;
    private final BillingReportService billingReports;
    private final LeaseService leases;
    private final LeaseReadService reads;
    private final LeaseTerminationService endings;

  public LeaseManagementController(
      LeaseService leases,
      LeaseReadService reads,
      LeaseTerminationService endings,
      BillingReportService billingReports,
      ConversationService conversations) {
    this.conversations = conversations;
        this.leases=leases;
        this.reads=reads;
        this.endings=endings;
        this.billingReports=billingReports;
    }
    @GetMapping
    public List<LeaseResponse> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @RequestParam(required=false) LeaseStatus status,
            @RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="0") int offset) {
        return reads.list(actor,organizationId,propertyId,status,limit,offset);
    }
    @GetMapping("/summary")
    public LeaseDashboardResponse summary(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId) {
        return reads.summary(actor,organizationId,propertyId);
    }
    @GetMapping("/billing-summary")
    public List<BillingCurrencySummaryResponse> billingSummary(@AuthenticationPrincipal AuthenticatedUser actor,@PathVariable UUID organizationId,@PathVariable UUID propertyId) {
        return billingReports.summary(actor,organizationId,propertyId);
    }
    @GetMapping("/{id}")
    public LeaseResponse get(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id) {
        return reads.manager(actor,organizationId,propertyId,id);
    }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public LeaseResponse create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @Valid @RequestBody CreateLeaseRequest request) {
        return leases.create(actor,organizationId,propertyId,request);
    }
    @PutMapping("/{id}")
    public LeaseResponse revise(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody ReviseLeaseRequest request) {
        return leases.revise(actor,organizationId,propertyId,id,request);
    }
    @PostMapping("/{id}/send")
    public LeaseResponse send(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody LeaseVersionRequest request) {
        return leases.send(actor,organizationId,propertyId,id,request);
    }
    @PostMapping("/{id}/recall")
    public LeaseResponse recall(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody LeaseReasonRequest request) {
        return leases.recall(actor,organizationId,propertyId,id,request);
    }
    @PostMapping("/{id}/cancel")
    public LeaseResponse cancel(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody LeaseReasonRequest request) {
        return leases.cancel(actor,organizationId,propertyId,id,request);
    }
    @PostMapping("/{id}/activate")
    public LeaseResponse activate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody ActivateLeaseRequest request) {
        return leases.activate(actor,organizationId,propertyId,id,request);
    }
    @PostMapping("/{id}/complete")
    public LeaseResponse complete(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody LeaseReasonRequest request) {
        return leases.complete(actor,organizationId,propertyId,id,request);
    }
    @PostMapping("/{id}/release-unit")
    public LeaseResponse releaseUnit(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody ReleaseLeaseUnitRequest request) {
        return leases.releaseUnit(actor,organizationId,propertyId,id,request);
    }
    @PostMapping("/{id}/sign")
    public LeaseResponse sign(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody SignLeaseRequest request) {
        return leases.sign(actor,organizationId,propertyId,id,request);
    }
    @GetMapping("/{id}/history")
    public List<LeaseEventResponse> history(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="0") int offset) {
        return reads.history(actor,organizationId,propertyId,id,limit,offset);
    }
    @GetMapping("/{id}/revisions")
    public List<LeaseRevisionResponse> revisions(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="0") int offset) {
        return reads.revisions(actor,organizationId,propertyId,id,limit,offset);
    }
    @GetMapping("/{id}/revisions/{revisionId}/document")
    public ResponseEntity<String> document(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @PathVariable UUID revisionId) {
        var revision=reads.revision(actor,organizationId,propertyId,id,revisionId);
    return AgreementHttpResponse.document(
        revision.document(), "lease-" + id + "-r" + revision.number() + ".txt");
    }
    @GetMapping("/{id}/revisions/{revisionId}/receipt")
    public LeaseSigningReceipt receipt(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @PathVariable UUID revisionId) {
        return reads.receipt(actor,organizationId,propertyId,id,revisionId);
    }
    @GetMapping("/{id}/terminations")
    public List<LeaseTerminationResponse> terminations(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="0") int offset) {
        return reads.terminations(actor,organizationId,propertyId,id,limit,offset);
    }
    @PostMapping("/{id}/terminations/preview")
    public LeaseTerminationPreviewResponse previewTermination(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody LeaseTerminationPreviewRequest request) {
        return endings.preview(actor,organizationId,propertyId,id,request);
    }
    @PostMapping("/{id}/terminations") @ResponseStatus(HttpStatus.CREATED)
    public LeaseResponse requestTermination(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @Valid @RequestBody CreateLeaseTerminationRequest request) {
        return endings.request(actor,organizationId,propertyId,id,request);
    }
    @GetMapping("/{id}/terminations/{terminationId}/document")
    public ResponseEntity<String> terminationDocument(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @PathVariable UUID terminationId) {
        var ending=reads.termination(actor,organizationId,propertyId,id,terminationId);
    return AgreementHttpResponse.document(
        ending.document(), "termination-" + terminationId + ".txt");
    }
    @PostMapping("/{id}/terminations/{terminationId}/accept")
    public LeaseResponse acceptTermination(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @PathVariable UUID terminationId,
            @Valid @RequestBody ConfirmLeaseTerminationRequest request) {
        return endings.accept(actor,organizationId,propertyId,id,terminationId,request);
    }
    @PostMapping("/{id}/terminations/{terminationId}/reject")
    public LeaseResponse rejectTermination(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @PathVariable UUID terminationId,
            @Valid @RequestBody LeaseReasonRequest request) {
        return endings.resolve(actor,organizationId,propertyId,id,terminationId,request,false);
    }
    @PostMapping("/{id}/terminations/{terminationId}/cancel")
    public LeaseResponse cancelTermination(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID organizationId,
            @PathVariable UUID propertyId,
            @PathVariable UUID id,
            @PathVariable UUID terminationId,
            @Valid @RequestBody LeaseReasonRequest request) {
        return endings.resolve(actor,organizationId,propertyId,id,terminationId,request,true);
    }

  @PostMapping("/{id}/conversations")
  public ConversationResponse conversation(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody OpenConversationRequest request) {
    return conversations.lease(actor, organizationId, propertyId, id, request);
  }
}
