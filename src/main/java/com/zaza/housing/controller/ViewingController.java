package com.zaza.housing.controller;

import com.zaza.housing.dto.ProposeViewingRequest;
import com.zaza.housing.dto.ViewingCommandRequest;
import com.zaza.housing.dto.ViewingHistoryResponse;
import com.zaza.housing.dto.ViewingResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.ViewingService;
import jakarta.validation.Valid;
import java.time.Instant;
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
public class ViewingController {
  private static final String BASE = "/api/v1/conversations/{conversationId}/viewings";
  private final ViewingService viewings;

  public ViewingController(ViewingService viewings) {
    this.viewings = viewings;
  }

  @GetMapping(BASE)
  public List<ViewingResponse> list(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return viewings.list(actor, null, null, conversationId, null, null, null, limit, offset);
  }

  @PostMapping(BASE)
  public ViewingResponse create(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @Valid @RequestBody ProposeViewingRequest request) {
    return viewings.create(actor, conversationId, request);
  }

  @GetMapping(BASE + "/{id}")
  public ViewingResponse get(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @PathVariable UUID id) {
    return viewings.get(actor, conversationId, id);
  }

  @GetMapping(BASE + "/{id}/history")
  public List<ViewingHistoryResponse> history(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return viewings.history(actor, conversationId, id, limit, offset);
  }

  @PostMapping(BASE + "/{id}/reschedule")
  public ViewingResponse reschedule(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @PathVariable UUID id,
      @Valid @RequestBody ProposeViewingRequest request) {
    return viewings.reschedule(actor, conversationId, id, request);
  }

  @GetMapping("/api/v1/me/viewings")
  public List<ViewingResponse> mine(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Instant from,
      @RequestParam(required = false) Instant to,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return viewings.list(actor, null, null, null, status, from, to, limit, offset);
  }

  @GetMapping("/api/v1/organizations/{organizationId}/properties/{propertyId}/viewings/calendar")
  public List<ViewingResponse> calendar(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID organizationId,
      @PathVariable UUID propertyId,
      @RequestParam Instant from,
      @RequestParam Instant to,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return viewings.list(actor, organizationId, propertyId, null, null, from, to, limit, offset);
  }

  @PostMapping(BASE + "/{id}/confirm")
  public ViewingResponse confirmed(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @PathVariable UUID id,
      @Valid @RequestBody ViewingCommandRequest request) {
    return viewings.decision(actor, conversationId, id, request, "CONFIRMED");
  }

  @PostMapping(BASE + "/{id}/decline")
  public ViewingResponse declined(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @PathVariable UUID id,
      @Valid @RequestBody ViewingCommandRequest request) {
    return viewings.decision(actor, conversationId, id, request, "DECLINED");
  }

  @PostMapping(BASE + "/{id}/cancel")
  public ViewingResponse cancelled(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @PathVariable UUID id,
      @Valid @RequestBody ViewingCommandRequest request) {
    return viewings.decision(actor, conversationId, id, request, "CANCELLED");
  }

  @PostMapping(BASE + "/{id}/complete")
  public ViewingResponse completed(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @PathVariable UUID id,
      @Valid @RequestBody ViewingCommandRequest request) {
    return viewings.decision(actor, conversationId, id, request, "COMPLETED");
  }

  @PostMapping(BASE + "/{id}/no-show")
  public ViewingResponse no_show(
      @AuthenticationPrincipal AuthenticatedUser actor,
      @PathVariable UUID conversationId,
      @PathVariable UUID id,
      @Valid @RequestBody ViewingCommandRequest request) {
    return viewings.decision(actor, conversationId, id, request, "NO_SHOW");
  }
}
