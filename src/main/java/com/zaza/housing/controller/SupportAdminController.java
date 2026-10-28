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
@RequestMapping("/api/v1/admin/operations")
public class SupportAdminController {
  private final SupportCaseService cases;
  private final SupportEvidenceService evidence;

  public SupportAdminController(SupportCaseService cases, SupportEvidenceService evidence) {
    this.cases = cases;
    this.evidence = evidence;
  }

  private String token(String h) {
    return AccountSecurityService.hash(h.substring(7));
  }

  @GetMapping("/support-cases")
  public List<SupportCaseResponse> list(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(defaultValue = "false") boolean overdue,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return cases.list(a, token(h), true, status, mine, overdue, limit, offset);
  }

  @GetMapping("/support-cases/{id}")
  public SupportCaseResponse get(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id) {
    return cases.get(a, token(h), id, true);
  }

  @GetMapping("/support-cases/{id}/messages")
  public List<Map<String, Object>> messages(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return cases.messages(a, token(h), id, true, limit, offset);
  }

  @PostMapping("/support-cases/{id}/messages")
  public SupportCaseResponse reply(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @Valid @RequestBody SupportMessageRequest r) {
    return cases.reply(a, token(h), id, true, r);
  }

  @GetMapping("/support-cases/{id}/history")
  public List<Map<String, Object>> history(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return cases.history(a, token(h), id, true, limit, offset);
  }

  @GetMapping("/support-cases/{id}/evidence")
  public List<SupportEvidenceResponse> evidence(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id) {
    return evidence.list(a, token(h), id, true);
  }

  @PostMapping(
      value = "/support-cases/{id}/evidence",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public SupportCaseResponse upload(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @RequestParam UUID requestId,
      @RequestParam long version,
      @RequestParam String caption,
      @RequestParam(defaultValue = "false") boolean internal,
      @RequestPart MultipartFile file) {
    return evidence.upload(a, token(h), id, true, requestId, version, caption, internal, file);
  }

  @GetMapping("/support-cases/{id}/evidence/{image}/content")
  public ResponseEntity<org.springframework.core.io.Resource> content(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @PathVariable UUID image) {
    var value = evidence.content(a, token(h), id, image, true);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .contentType(MediaType.parseMediaType(value.contentType()))
        .contentLength(value.byteSize())
        .body(value.resource());
  }

  @PostMapping("/support-cases/{id}/{command}")
  public SupportCaseResponse command(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @PathVariable String command,
      @Valid @RequestBody AdministrationCommandRequest r) {
    return cases.adminCommand(a, token(h), id, command, r);
  }

  @GetMapping("/support-cases/{id}/operators")
  public List<Map<String, Object>> operators(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id) {
    return cases.operators(a, token(h), id);
  }
}
