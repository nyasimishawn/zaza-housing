package com.zaza.housing.controller;

import com.zaza.housing.dto.AssignWorkRequest;
import com.zaza.housing.dto.CompleteWorkRequest;
import com.zaza.housing.dto.ConsentWorkRequest;
import com.zaza.housing.dto.CreateWorkRequest;
import com.zaza.housing.dto.ProposeWorkVisitRequest;
import com.zaza.housing.dto.ServiceQuoteResponse;
import com.zaza.housing.dto.TriageWorkRequest;
import com.zaza.housing.dto.WorkCommandRequest;
import com.zaza.housing.dto.WorkDashboardResponse;
import com.zaza.housing.dto.WorkDisputeResponse;
import com.zaza.housing.dto.WorkEventResponse;
import com.zaza.housing.dto.WorkEvidenceResponse;
import com.zaza.housing.dto.WorkNoteRequest;
import com.zaza.housing.dto.WorkView;
import com.zaza.housing.dto.WorkVisitResponse;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkPriority;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.WorkCompletionService;
import com.zaza.housing.service.WorkEvidenceService;
import com.zaza.housing.service.WorkReadService;
import com.zaza.housing.service.WorkRequestService;
import com.zaza.housing.service.WorkVisitService;
import com.zaza.housing.util.ImageHttpResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/work-requests")
public class MaintenanceController {
  private final WorkReadService reads;
  private final WorkRequestService requests;
  private final WorkVisitService visits;
  private final WorkCompletionService completion;
  private final WorkEvidenceService evidence;

  public MaintenanceController(
      WorkReadService reads,
      WorkRequestService requests,
      WorkVisitService visits,
      WorkCompletionService completion,
      WorkEvidenceService evidence) {
    this.reads = reads;
    this.requests = requests;
    this.visits = visits;
    this.completion = completion;
    this.evidence = evidence;
  }

  @GetMapping
  public List<WorkView> list(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @RequestParam(required = false) WorkStatus status,
      @RequestParam(required = false) WorkKind kind,
      @RequestParam(required = false) WorkPriority priority,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.list(
        actor,
        WorkParty.COORDINATOR,
        organizationId,
        propertyId,
        status,
        kind,
        priority,
        limit,
        offset);
  }

  @GetMapping("/summary")
  public WorkDashboardResponse summary(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId) {
    return reads.summary(actor, WorkParty.COORDINATOR, organizationId, propertyId);
  }

  @GetMapping("/{id}")
  public WorkView get(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id) {
    return reads.get(actor, WorkParty.COORDINATOR, organizationId, propertyId, id);
  }

  @GetMapping("/{id}/history")
  public List<WorkEventResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.history(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/{id}/quotes")
  public List<ServiceQuoteResponse> quotes(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.quotes(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/{id}/visits")
  public List<WorkVisitResponse> visits(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.visits(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/{id}/disputes")
  public List<WorkDisputeResponse> disputes(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.disputes(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/{id}/evidence")
  public List<WorkEvidenceResponse> evidence(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return evidence.list(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, limit, offset);
  }

  @GetMapping("/{id}/evidence/{imageId}/content")
  public ResponseEntity<Resource> image(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID imageId) {
    return ImageHttpResponse.content(
        evidence.content(actor, WorkParty.COORDINATOR, organizationId, propertyId, id, imageId));
  }

  @PostMapping(value = "/{id}/evidence", consumes = "multipart/form-data")
  public WorkView upload(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @RequestParam UUID requestId,
      @RequestParam long version,
      @RequestParam(defaultValue = "SHARED") WorkVisibility visibility,
      @RequestParam String caption,
      @RequestPart("file") MultipartFile file) {
    return evidence.upload(
        actor,
        WorkParty.COORDINATOR,
        organizationId,
        propertyId,
        id,
        requestId,
        version,
        visibility,
        caption,
        file);
  }

  @PostMapping
  public WorkView create(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @Valid @RequestBody CreateWorkRequest request) {
    return requests.create(actor, organizationId, propertyId, request);
  }

  @PostMapping("/{id}/quotes/{quoteId}/accept")
  public WorkView acceptQuote(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @PathVariable UUID quoteId,
      @Valid @RequestBody ConsentWorkRequest request) {
    return visits.acceptQuote(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, quoteId, request);
  }

  @PostMapping("/{id}/notes")
  public WorkView note(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkNoteRequest request) {
    return requests.note(actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request);
  }

  @PostMapping("/{id}/disputes")
  public WorkView dispute(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return completion.dispute(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request);
  }

  @PostMapping("/{id}/withdraw-dispute")
  public WorkView withdrawDispute(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return completion.resolve(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request, false);
  }

  @PostMapping("/{id}/reschedule")
  public WorkView reschedule(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return visits.reschedule(actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request);
  }

  @PostMapping("/{id}/triage")
  public WorkView triage(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody TriageWorkRequest request) {
    return requests.triage(actor, organizationId, propertyId, id, request, false);
  }

  @PostMapping("/{id}/request-information")
  public WorkView information(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody TriageWorkRequest request) {
    return requests.triage(actor, organizationId, propertyId, id, request, true);
  }

  @PostMapping("/{id}/assign")
  public WorkView assign(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody AssignWorkRequest request) {
    return requests.assign(actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request);
  }

  @PostMapping("/{id}/assign-internal")
  public WorkView internal(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return requests.internal(actor, organizationId, propertyId, id, request);
  }

  @PostMapping("/{id}/reject")
  public WorkView reject(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return requests.cancel(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request, true);
  }

  @PostMapping("/{id}/cancel")
  public WorkView cancel(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return requests.cancel(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request, false);
  }

  @PostMapping("/{id}/visits")
  public WorkView visit(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody ProposeWorkVisitRequest request) {
    return visits.propose(actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request);
  }

  @PostMapping("/{id}/start")
  public WorkView start(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return visits.start(actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request);
  }

  @PostMapping("/{id}/submit-completion")
  public WorkView submitCompletion(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody CompleteWorkRequest request) {
    return completion.submit(actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request);
  }

  @PostMapping("/{id}/approve-completion")
  public WorkView approve(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody ConsentWorkRequest request) {
    return completion.approve(actor, organizationId, propertyId, id, request);
  }

  @PostMapping("/{id}/rework")
  public WorkView rework(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return completion.resolve(
        actor, WorkParty.COORDINATOR, organizationId, propertyId, id, request, true);
  }
}
