package com.zaza.housing.controller;

import com.zaza.housing.dto.CreateVerificationRequest;
import com.zaza.housing.dto.DocumentCommandRequest;
import com.zaza.housing.dto.DocumentEventResponse;
import com.zaza.housing.dto.ReviseVerificationRequest;
import com.zaza.housing.dto.SubmitVerificationRequest;
import com.zaza.housing.dto.VerificationResponse;
import com.zaza.housing.dto.VerificationSubmissionResponse;
import com.zaza.housing.dto.VerificationSummaryResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.VerificationReadService;
import com.zaza.housing.service.VerificationService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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
@RequestMapping({
  "/api/v1/me/verifications",
  "/api/v1/organizations/{organizationId}/verifications"
})
public class VerificationController {
  private final VerificationService cases;
  private final VerificationReadService reads;

  public VerificationController(VerificationService cases, VerificationReadService reads) {
    this.cases = cases;
    this.reads = reads;
  }

  @GetMapping
  public List<VerificationResponse> list(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.list(actor, organizationId, limit, offset);
  }

  @GetMapping("/status")
  public VerificationSummaryResponse status(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId) {
    return reads.summary(actor, organizationId);
  }

  @GetMapping("/{id}")
  public VerificationResponse get(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id) {
    return reads.get(actor, organizationId, id);
  }

  @GetMapping("/{id}/history")
  public List<DocumentEventResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.history(actor, organizationId, id, limit, offset);
  }

  @GetMapping("/{id}/submissions")
  public List<VerificationSubmissionResponse> submissions(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return reads.submissions(actor, organizationId, id, limit, offset);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public VerificationResponse create(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @Valid @RequestBody CreateVerificationRequest request) {
    return cases.create(actor, organizationId, request);
  }

  @PutMapping("/{id}")
  public VerificationResponse revise(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @Valid @RequestBody ReviseVerificationRequest request) {
    return cases.revise(actor, organizationId, id, request);
  }

  @PostMapping("/{id}/submit")
  public VerificationResponse submit(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @Valid @RequestBody SubmitVerificationRequest request) {
    return cases.submit(actor, organizationId, id, request);
  }

  @PostMapping("/{id}/withdraw")
  public VerificationResponse withdraw(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable(required = false) UUID organizationId,
      @PathVariable UUID id,
      @Valid @RequestBody DocumentCommandRequest request) {
    return cases.withdraw(actor, organizationId, id, request);
  }
}
