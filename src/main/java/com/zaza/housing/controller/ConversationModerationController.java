package com.zaza.housing.controller;

import com.zaza.housing.dto.ConversationReportEvidenceResponse;
import com.zaza.housing.dto.ConversationReportQueueResponse;
import com.zaza.housing.dto.ConversationReportResponse;
import com.zaza.housing.dto.ResolveConversationReportRequest;
import com.zaza.housing.dto.ReviewConversationReportRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.ConversationModerationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/conversation-reports")
public class ConversationModerationController {
  private final ConversationModerationService reports;

  public ConversationModerationController(ConversationModerationService reports) {
    this.reports = reports;
  }

  @GetMapping
  public List<ConversationReportQueueResponse> queue(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reports.queue(actor, status, mine, limit, offset);
  }

  @GetMapping("/{id}/evidence")
  public ConversationReportEvidenceResponse evidence(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return reports.evidence(actor, id);
  }

  @PostMapping("/{id}/claim")
  public ConversationReportResponse claim(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ReviewConversationReportRequest request) {
    return reports.claim(actor, id, request);
  }

  @PostMapping("/{id}/release")
  public ConversationReportResponse release(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ReviewConversationReportRequest request) {
    return reports.release(actor, id, request);
  }

  @PostMapping("/{id}/resolve")
  public ConversationReportResponse resolve(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody ResolveConversationReportRequest request) {
    return reports.resolve(actor, id, request);
  }
}
