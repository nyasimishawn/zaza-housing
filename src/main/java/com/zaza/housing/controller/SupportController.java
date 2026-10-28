package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/me/support")
public class SupportController {
  private final SupportCaseService cases;
  private final SupportEvidenceService evidence;

  public SupportController(SupportCaseService cases, SupportEvidenceService evidence) {
    this.cases = cases;
    this.evidence = evidence;
  }

  private String token(String h) {
    return AccountSecurityService.hash(h.substring(7));
  }

  @GetMapping("/cases")
  public List<SupportCaseResponse> list(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(defaultValue = "false") boolean overdue,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return cases.list(a, token(h), false, status, mine, overdue, limit, offset);
  }

  @GetMapping("/cases/{id}")
  public SupportCaseResponse get(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id) {
    return cases.get(a, token(h), id, false);
  }

  @GetMapping("/cases/{id}/messages")
  public List<Map<String, Object>> messages(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return cases.messages(a, token(h), id, false, limit, offset);
  }

  @PostMapping("/cases/{id}/messages")
  public SupportCaseResponse reply(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @Valid @RequestBody SupportMessageRequest r) {
    return cases.reply(a, token(h), id, false, r);
  }

  @GetMapping("/cases/{id}/history")
  public List<Map<String, Object>> history(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return cases.history(a, token(h), id, false, limit, offset);
  }

  @GetMapping("/cases/{id}/evidence")
  public List<SupportEvidenceResponse> evidence(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id) {
    return evidence.list(a, token(h), id, false);
  }

  @PostMapping(value = "/cases/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public SupportCaseResponse upload(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @RequestParam UUID requestId,
      @RequestParam long version,
      @RequestParam String caption,
      @RequestParam(defaultValue = "false") boolean internal,
      @RequestPart MultipartFile file) {
    return evidence.upload(a, token(h), id, false, requestId, version, caption, internal, file);
  }

  @GetMapping("/cases/{id}/evidence/{image}/content")
  public ResponseEntity<org.springframework.core.io.Resource> content(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @PathVariable UUID image) {
    var value = evidence.content(a, token(h), id, image, false);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(value.contentType()))
        .contentLength(value.byteSize())
        .body(value.resource());
  }

  @PostMapping("/cases")
  public SupportCaseResponse create(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody SupportCaseRequest r) {
    return cases.create(a, token(h), r);
  }

  @PostMapping("/cases/{id}/{command}")
  public SupportCaseResponse command(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @PathVariable String command,
      @Valid @RequestBody SupportCaseCommandRequest r) {
    return cases.reporterCommand(a, token(h), id, command, r);
  }

  @GetMapping("/notifications")
  public List<Map<String, Object>> notices(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return cases.notifications(a, token(h), limit, offset);
  }

  @PostMapping("/notifications/{id}/read")
  public SecurityMessageResponse read(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id) {
    cases.read(a, token(h), id);
    return new SecurityMessageResponse("Support update marked as read.");
  }
}
