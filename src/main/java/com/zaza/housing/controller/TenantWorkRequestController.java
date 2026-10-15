package com.zaza.housing.controller;

import com.zaza.housing.dto.AssignWorkRequest;
import com.zaza.housing.dto.ConsentWorkRequest;
import com.zaza.housing.dto.ConversationResponse;
import com.zaza.housing.dto.CreateServiceReviewRequest;
import com.zaza.housing.dto.CreateWorkRequest;
import com.zaza.housing.dto.OpenConversationRequest;
import com.zaza.housing.dto.OpenServiceAccountRequest;
import com.zaza.housing.dto.ServiceAccountResponse;
import com.zaza.housing.dto.ServiceQuoteResponse;
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
import com.zaza.housing.service.ConversationService;
import com.zaza.housing.service.ServicePaymentService;
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
@RequestMapping("/api/v1/me/work-requests")
public class TenantWorkRequestController {
  private final ConversationService conversations;
  private final ServicePaymentService servicePayments;
  private final WorkReadService reads;
  private final WorkRequestService requests;
  private final WorkVisitService visits;
  private final WorkCompletionService completion;
  private final WorkEvidenceService evidence;

  public TenantWorkRequestController(
      WorkReadService reads,
      WorkRequestService requests,
      WorkVisitService visits,
      WorkCompletionService completion,
      WorkEvidenceService evidence,
      ServicePaymentService servicePayments,
      ConversationService conversations) {
    this.conversations = conversations;
    this.reads = reads;
    this.requests = requests;
    this.visits = visits;
    this.completion = completion;
    this.servicePayments = servicePayments;
    this.evidence = evidence;
  }

  @GetMapping
  public List<WorkView> list(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(required = false) WorkStatus status,
      @RequestParam(required = false) WorkKind kind,
      @RequestParam(required = false) WorkPriority priority,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.list(actor, WorkParty.CUSTOMER, null, null, status, kind, priority, limit, offset);
  }

  @GetMapping("/summary")
  public WorkDashboardResponse summary(@AuthenticationPrincipal AuthenticatedUser actor) {
    return reads.summary(actor, WorkParty.CUSTOMER, null, null);
  }

  @GetMapping("/{id}")
  public WorkView get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return reads.get(actor, WorkParty.CUSTOMER, null, null, id);
  }

  @GetMapping("/{id}/history")
  public List<WorkEventResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.history(actor, WorkParty.CUSTOMER, null, null, id, limit, offset);
  }

  @GetMapping("/{id}/quotes")
  public List<ServiceQuoteResponse> quotes(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.quotes(actor, WorkParty.CUSTOMER, null, null, id, limit, offset);
  }

  @GetMapping("/{id}/visits")
  public List<WorkVisitResponse> visits(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.visits(actor, WorkParty.CUSTOMER, null, null, id, limit, offset);
  }

  @GetMapping("/{id}/disputes")
  public List<WorkDisputeResponse> disputes(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.disputes(actor, WorkParty.CUSTOMER, null, null, id, limit, offset);
  }

  @GetMapping("/{id}/evidence")
  public List<WorkEvidenceResponse> evidence(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return evidence.list(actor, WorkParty.CUSTOMER, null, null, id, limit, offset);
  }

  @GetMapping("/{id}/evidence/{imageId}/content")
  public ResponseEntity<Resource> image(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID imageId) {
    return ImageHttpResponse.content(
        evidence.content(actor, WorkParty.CUSTOMER, null, null, id, imageId));
  }

  @PostMapping(value = "/{id}/evidence", consumes = "multipart/form-data")
  public WorkView upload(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam UUID requestId,
      @RequestParam long version,
      @RequestParam(defaultValue = "SHARED") WorkVisibility visibility,
      @RequestParam String caption,
      @RequestPart("file") MultipartFile file) {
    return evidence.upload(
        actor, WorkParty.CUSTOMER, null, null, id, requestId, version, visibility, caption, file);
  }

  @PostMapping
  public WorkView create(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @Valid @RequestBody CreateWorkRequest request) {
    return requests.create(actor, null, null, request);
  }

  @PostMapping("/{id}/quotes/{quoteId}/accept")
  public WorkView acceptQuote(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID quoteId,
      @Valid @RequestBody ConsentWorkRequest request) {
    return visits.acceptQuote(actor, WorkParty.CUSTOMER, null, null, id, quoteId, request);
  }

  @PostMapping("/{id}/visits/{visitId}/accept")
  public WorkView acceptVisit(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID visitId,
      @Valid @RequestBody ConsentWorkRequest request) {
    return visits.accept(actor, id, visitId, request);
  }

  @PostMapping("/{id}/notes")
  public WorkView note(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody WorkNoteRequest request) {
    return requests.note(actor, WorkParty.CUSTOMER, null, null, id, request);
  }

  @PostMapping("/{id}/disputes")
  public WorkView dispute(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return completion.dispute(actor, WorkParty.CUSTOMER, null, null, id, request);
  }

  @PostMapping("/{id}/withdraw-dispute")
  public WorkView withdrawDispute(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return completion.resolve(actor, WorkParty.CUSTOMER, null, null, id, request, false);
  }

  @PostMapping("/{id}/reschedule")
  public WorkView reschedule(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return visits.reschedule(actor, WorkParty.CUSTOMER, null, null, id, request);
  }

  @PostMapping("/{id}/respond")
  public WorkView respond(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return requests.respond(actor, id, request);
  }

  @PostMapping("/{id}/assign")
  public WorkView assign(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody AssignWorkRequest request) {
    return requests.assign(actor, WorkParty.CUSTOMER, null, null, id, request);
  }

  @PostMapping("/{id}/cancel")
  public WorkView cancel(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return requests.cancel(actor, WorkParty.CUSTOMER, null, null, id, request, false);
  }

  @PostMapping("/{id}/confirm-completion")
  public WorkView confirmCompletion(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ConsentWorkRequest request) {
    return completion.confirm(actor, id, request);
  }

  @PostMapping("/{id}/rework")
  public WorkView rework(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody WorkCommandRequest request) {
    return completion.resolve(actor, WorkParty.CUSTOMER, null, null, id, request, true);
  }

  @PostMapping("/{id}/reviews")
  public WorkView review(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody CreateServiceReviewRequest request) {
    return completion.review(actor, id, request);
  }

  @PostMapping("/{id}/payments")
  public ServiceAccountResponse openServicePayments(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody OpenServiceAccountRequest request) {
    return servicePayments.open(actor, WorkParty.CUSTOMER, null, null, id, request);
  }

  @PostMapping("/{id}/conversations")
  public ConversationResponse conversation(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody OpenConversationRequest request) {
    return conversations.work(actor, WorkParty.CUSTOMER, null, null, id, request);
  }
}
