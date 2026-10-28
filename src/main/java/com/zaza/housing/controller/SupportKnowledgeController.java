package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class SupportKnowledgeController {
  private final SupportKnowledgeService service;

  public SupportKnowledgeController(SupportKnowledgeService service) {
    this.service = service;
  }

  private String token(String h) {
    return AccountSecurityService.hash(h.substring(7));
  }

  @GetMapping("/api/v1/support/topics")
  public List<Map<String, Object>> topics(
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.publicTopics(q, limit, offset);
  }

  @GetMapping("/api/v1/support/topics/{id}")
  public Map<String, Object> topic(@PathVariable UUID id) {
    return service.get(id, false);
  }

  @GetMapping("/api/v1/admin/operations/knowledge")
  public List<Map<String, Object>> list(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.list(a, token(h), q, limit, offset);
  }

  @PostMapping("/api/v1/admin/operations/knowledge")
  public Map<String, Object> create(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @Valid @RequestBody SupportKnowledgeRequest r) {
    return service.save(a, token(h), null, r);
  }

  @PutMapping("/api/v1/admin/operations/knowledge/{id}")
  public Map<String, Object> revise(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @Valid @RequestBody SupportKnowledgeRequest r) {
    return service.save(a, token(h), id, r);
  }

  @PostMapping("/api/v1/admin/operations/knowledge/{id}/{command}")
  public Map<String, Object> command(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestHeader("Authorization") String h,
      @PathVariable UUID id,
      @PathVariable String command,
      @Valid @RequestBody AdministrationCommandRequest r) {
    return service.command(a, token(h), id, command, r);
  }
}
