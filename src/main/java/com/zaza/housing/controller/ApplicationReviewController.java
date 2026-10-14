package com.zaza.housing.controller;

import com.zaza.housing.dto.ApplicationDashboardResponse;
import com.zaza.housing.dto.ApplicationDecisionRequest;
import com.zaza.housing.dto.ApplicationEventResponse;
import com.zaza.housing.dto.ApplicationNoteResponse;
import com.zaza.housing.dto.ApplicationVersionRequest;
import com.zaza.housing.dto.CreateApplicationNoteRequest;
import com.zaza.housing.dto.ReviewApplicationResponse;
import com.zaza.housing.enums.ApplicationStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.RentalApplicationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/applications")
public class ApplicationReviewController {
    private final RentalApplicationService applications;
    public ApplicationReviewController(RentalApplicationService applications) { this.applications = applications; }
    @GetMapping
    public List<ReviewApplicationResponse> list(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId,
            @RequestParam(required = false) ApplicationStatus status, @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) {
        return applications.listReview(actor, organizationId, propertyId, status, limit, offset);
    }
    @GetMapping("/summary")
    public ApplicationDashboardResponse summary(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId) {
        return applications.reviewDashboard(actor, organizationId, propertyId);
    }
    @GetMapping("/{id}")
    public ReviewApplicationResponse get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId, @PathVariable UUID id) {
        return applications.getReview(actor, organizationId, propertyId, id);
    }
    @PostMapping("/{id}/review")
    public ReviewApplicationResponse review(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId,
            @PathVariable UUID id, @Valid @RequestBody ApplicationVersionRequest request) { return applications.startReview(actor, organizationId, propertyId, id, request); }
    @PostMapping("/{id}/request-information")
    public ReviewApplicationResponse requestInformation(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId,
            @PathVariable UUID id, @Valid @RequestBody ApplicationDecisionRequest request) { return applications.requestInformation(actor, organizationId, propertyId, id, request); }
    @PostMapping("/{id}/approve")
    public ReviewApplicationResponse approve(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId,
            @PathVariable UUID id, @Valid @RequestBody ApplicationDecisionRequest request) { return applications.approve(actor, organizationId, propertyId, id, request); }
    @PostMapping("/{id}/reject")
    public ReviewApplicationResponse reject(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId,
            @PathVariable UUID id, @Valid @RequestBody ApplicationDecisionRequest request) { return applications.reject(actor, organizationId, propertyId, id, request); }
    @PostMapping("/{id}/cancel-approval")
    public ReviewApplicationResponse cancelApproval(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId,
            @PathVariable UUID id, @Valid @RequestBody ApplicationDecisionRequest request) { return applications.cancelApproval(actor, organizationId, propertyId, id, request); }
    @GetMapping("/{id}/history")
    public List<ApplicationEventResponse> history(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId, @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) { return applications.reviewHistory(actor, organizationId, propertyId, id, limit, offset); }
    @GetMapping("/{id}/notes")
    public List<ApplicationNoteResponse> notes(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId, @PathVariable UUID id,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "0") int offset) { return applications.notes(actor, organizationId, propertyId, id, limit, offset); }
    @PostMapping("/{id}/notes") @ResponseStatus(HttpStatus.CREATED)
    public ApplicationNoteResponse addNote(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId, @PathVariable UUID propertyId,
            @PathVariable UUID id, @Valid @RequestBody CreateApplicationNoteRequest request) { return applications.addNote(actor, organizationId, propertyId, id, request); }
}
