package com.zaza.housing.controller;

import com.zaza.housing.dto.DecideVerificationRequest;
import com.zaza.housing.dto.DocumentEventResponse;
import com.zaza.housing.dto.VerificationCommandRequest;
import com.zaza.housing.dto.VerificationEvidenceResponse;
import com.zaza.housing.dto.VerificationQueueResponse;
import com.zaza.housing.dto.VerificationResponse;
import com.zaza.housing.enums.VerificationStatus;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.VerificationAdminService;
import com.zaza.housing.service.VerificationReadService;
import com.zaza.housing.util.DocumentHttpResponse;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/verifications")
public class VerificationAdminController {
  private final VerificationAdminService cases;
  private final VerificationReadService reads;

  public VerificationAdminController(
      VerificationAdminService cases, VerificationReadService reads) {
    this.cases = cases;
    this.reads = reads;
  }

  @GetMapping
  public List<VerificationQueueResponse> queue(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(required = false) VerificationStatus status,
      @RequestParam(defaultValue = "false") boolean assignedToMe,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.queue(actor, status, assignedToMe, limit, offset);
  }

  @GetMapping("/{id}")
  public VerificationResponse get(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return reads.review(actor, id);
  }

  @GetMapping("/{id}/evidence")
  public List<VerificationEvidenceResponse> evidence(
      @AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
    return reads.evidence(actor, id);
  }

  @GetMapping("/{id}/evidence/{versionId}/content")
  public ResponseEntity<Resource> content(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @PathVariable UUID versionId) {
    return DocumentHttpResponse.content(reads.content(actor, id, versionId));
  }

  @GetMapping("/{id}/history")
  public List<DocumentEventResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.reviewHistory(actor, id, limit, offset);
  }

  @PostMapping("/{id}/claim")
  public VerificationQueueResponse claim(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody VerificationCommandRequest request) {
    return cases.claim(actor, id, request);
  }

  @PostMapping("/{id}/release")
  public VerificationQueueResponse release(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody VerificationCommandRequest request) {
    return cases.release(actor, id, request);
  }

  @PostMapping("/{id}/decide")
  public VerificationQueueResponse decide(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody DecideVerificationRequest request) {
    return cases.decide(actor, id, request);
  }

  @PostMapping("/{id}/revoke")
  public VerificationQueueResponse revoke(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID id,
      @Valid @RequestBody VerificationCommandRequest request) {
    return cases.revoke(actor, id, request);
  }
}
