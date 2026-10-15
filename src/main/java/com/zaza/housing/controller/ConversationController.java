package com.zaza.housing.controller;

import com.zaza.housing.dto.ConversationCommandRequest;
import com.zaza.housing.dto.ConversationEventResponse;
import com.zaza.housing.dto.ConversationInboxSummaryResponse;
import com.zaza.housing.dto.ConversationMessageResponse;
import com.zaza.housing.dto.ConversationPreferenceRequest;
import com.zaza.housing.dto.ConversationReportResponse;
import com.zaza.housing.dto.ConversationResponse;
import com.zaza.housing.dto.CreateEnquiryRequest;
import com.zaza.housing.dto.EnquiryListingResponse;
import com.zaza.housing.dto.MarkConversationReadRequest;
import com.zaza.housing.dto.RedactConversationMessageRequest;
import com.zaza.housing.dto.ReportConversationMessageRequest;
import com.zaza.housing.dto.SendMessageRequest;
import com.zaza.housing.enums.ConversationKind;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.ConversationModerationService;
import com.zaza.housing.service.ConversationService;
import com.zaza.housing.service.ListingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConversationController {
  private static final String BASE = "/api/v1/conversations";
  private final ConversationService conversations;
  private final ConversationModerationService reports;
  private final ListingService listings;

  public ConversationController(
      ConversationService conversations,
      ConversationModerationService reports,
      ListingService listings) {
    this.conversations = conversations;
    this.reports = reports;
    this.listings = listings;
  }

  @GetMapping("/api/v1/listings/{listingId}/contact")
  public EnquiryListingResponse contact(@PathVariable UUID listingId) {
    return listings.contactListing(listingId);
  }

  @PostMapping("/api/v1/listings/{listingId}/enquiries")
  public ConversationResponse enquiry(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID listingId,
      @Valid @RequestBody CreateEnquiryRequest request) {
    return conversations.enquiry(actor, listingId, request);
  }

  @GetMapping("/api/v1/me/conversations")
  public List<ConversationResponse> mine(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(required = false) ConversationKind kind,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return conversations.list(actor, null, kind, status, unreadOnly, limit, offset);
  }

  @GetMapping("/api/v1/me/conversations/summary")
  public ConversationInboxSummaryResponse mineSummary(
      @AuthenticationPrincipal AuthenticatedUser actor) {
    return conversations.summary(actor, null);
  }

  @GetMapping("/api/v1/organizations/{organizationId}/conversations")
  public List<ConversationResponse> organization(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @RequestParam(required = false) ConversationKind kind,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return conversations.list(actor, organizationId, kind, status, unreadOnly, limit, offset);
  }

  @GetMapping("/api/v1/organizations/{organizationId}/conversations/summary")
  public ConversationInboxSummaryResponse organizationSummary(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID organizationId) {
    return conversations.summary(actor, organizationId);
  }

  @GetMapping(BASE + "/{id}")
  public ConversationResponse get(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return conversations.get(actor, id);
  }

  @GetMapping(BASE + "/{id}/messages")
  public List<ConversationMessageResponse> messages(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "50") int limit) {
    return conversations.messages(actor, id, after, limit);
  }

  @GetMapping(BASE + "/{id}/history")
  public List<ConversationEventResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return conversations.history(actor, id, limit, offset);
  }

  @PostMapping(BASE + "/{id}/messages/{messageId}/redact")
  public ConversationResponse redact(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID messageId,
      @Valid @RequestBody RedactConversationMessageRequest request) {
    return conversations.redact(actor, id, messageId, request);
  }

  @PostMapping(BASE + "/{id}/messages/{messageId}/reports")
  public ConversationReportResponse report(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID messageId,
      @Valid @RequestBody ReportConversationMessageRequest request) {
    return reports.report(actor, id, messageId, request);
  }

  @GetMapping("/api/v1/me/conversation-reports")
  public List<ConversationReportResponse> reports(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reports.own(actor, limit, offset);
  }

  @GetMapping("/api/v1/me/conversation-reports/{reportId}")
  public ConversationReportResponse report(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID reportId) {
    return reports.own(actor, reportId);
  }

  @PostMapping(BASE + "/{id}/messages")
  public ConversationResponse send(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody SendMessageRequest request) {
    return conversations.send(actor, id, request);
  }

  @PostMapping(BASE + "/{id}/read")
  public ConversationResponse read(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody MarkConversationReadRequest request) {
    return conversations.read(actor, id, request);
  }

  @PostMapping(BASE + "/{id}/preferences")
  public ConversationResponse preferences(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ConversationPreferenceRequest request) {
    return conversations.preferences(actor, id, request);
  }

  @PostMapping(BASE + "/{id}/close")
  public ConversationResponse close(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ConversationCommandRequest request) {
    return conversations.status(actor, id, request, true);
  }

  @PostMapping(BASE + "/{id}/reopen")
  public ConversationResponse reopen(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ConversationCommandRequest request) {
    return conversations.status(actor, id, request, false);
  }
}
