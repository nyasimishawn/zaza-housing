package com.zaza.housing.controller;

import com.zaza.housing.dto.PropertyTaskActionRequest;
import com.zaza.housing.dto.PropertyTaskCreateRequest;
import com.zaza.housing.dto.PropertyTaskEvidenceResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.CaretakerEvent;
import com.zaza.housing.model.PropertyTask;
import com.zaza.housing.service.PropertyTaskEvidenceService;
import com.zaza.housing.service.PropertyTaskService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/v1/organizations/{org}/caretaker/tasks")
public class PropertyTaskController {
  private final PropertyTaskService tasks;
  private final PropertyTaskEvidenceService evidence;

  public PropertyTaskController(PropertyTaskService tasks, PropertyTaskEvidenceService evidence) {
    this.tasks = tasks;
    this.evidence = evidence;
  }

  @GetMapping
  public List<PropertyTask> list(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID assigneeId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return tasks.list(a, org, propertyId, status, assigneeId, limit, offset);
  }

  @PostMapping
  public PropertyTask create(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestBody @Valid PropertyTaskCreateRequest r) {
    return tasks.create(a, org, r);
  }

  @GetMapping("/{id}")
  public PropertyTask get(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org, @PathVariable UUID id) {
    return tasks.get(a, org, id);
  }

  @GetMapping("/{id}/history")
  public List<CaretakerEvent> history(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return tasks.history(a, org, id, limit, offset);
  }

  @PostMapping("/{id}/{command}")
  public PropertyTask command(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @PathVariable String command,
      @RequestBody @Valid PropertyTaskActionRequest r) {
    return tasks.command(a, org, id, command, r);
  }

  @GetMapping("/{id}/evidence")
  public List<PropertyTaskEvidenceResponse> evidence(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org, @PathVariable UUID id) {
    return evidence.list(a, org, id);
  }

  @PostMapping(value = "/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public PropertyTask upload(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @RequestParam UUID requestId,
      @RequestParam long version,
      @RequestParam String caption,
      @RequestPart MultipartFile file) {
    return evidence.upload(a, org, id, requestId, version, caption, file);
  }

  @GetMapping("/{id}/evidence/{image}/content")
  public ResponseEntity<org.springframework.core.io.Resource> content(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @PathVariable UUID image) {
    var c = evidence.content(a, org, id, image);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .contentType(MediaType.parseMediaType(c.contentType()))
        .body(c.resource());
  }
}
