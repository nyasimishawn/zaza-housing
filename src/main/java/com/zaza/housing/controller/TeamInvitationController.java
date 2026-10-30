package com.zaza.housing.controller;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.TeamInvitationService;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public class TeamInvitationController {
  private final TeamInvitationService invitations;

  public TeamInvitationController(TeamInvitationService invitations) {
    this.invitations = invitations;
  }

  @GetMapping("/api/v1/organizations/{org}/invitations/{id}")
  public TeamInvitationResponse businessDetail(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org, @PathVariable UUID id) {
    return invitations.detail(a, org, id);
  }

  @GetMapping("/api/v1/me/team-invitations/{id}")
  public TeamInvitationResponse recipientDetail(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID id) {
    return invitations.detail(a, null, id);
  }

  @GetMapping("/api/v1/organizations/{org}/invitations")
  public List<TeamInvitationResponse> business(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return invitations.list(a, org, status, limit, offset);
  }

  @PostMapping("/api/v1/organizations/{org}/invitations")
  @ResponseStatus(HttpStatus.CREATED)
  public TeamInvitationResponse create(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @Valid @RequestBody CreateTeamInvitationRequest r) {
    return invitations.create(a, org, r);
  }

  @PostMapping("/api/v1/organizations/{org}/invitations/{id}/resend")
  public TeamInvitationResponse resend(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @Valid @RequestBody InvitationCommand r) {
    return invitations.manage(a, org, id, r.version(), "resend");
  }

  @PostMapping("/api/v1/organizations/{org}/invitations/{id}/cancel")
  public TeamInvitationResponse cancel(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @Valid @RequestBody InvitationCommand r) {
    return invitations.manage(a, org, id, r.version(), "cancel");
  }

  @GetMapping("/api/v1/organizations/{org}/invitations/{id}/history")
  public List<TeamInvitationEventResponse> businessHistory(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return invitations.history(a, org, id, limit, offset);
  }

  @GetMapping("/api/v1/me/team-invitations")
  public List<TeamInvitationResponse> incoming(
      @AuthenticationPrincipal AuthenticatedUser a,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return invitations.list(a, null, status, limit, offset);
  }

  @GetMapping("/api/v1/me/team-invitations/{id}/history")
  public List<TeamInvitationEventResponse> recipientHistory(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return invitations.history(a, null, id, limit, offset);
  }

  @PostMapping("/api/v1/me/team-invitations/{id}/accept")
  public TeamInvitationResponse accept(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID id,
      @Valid @RequestBody InvitationCommand r) {
    return invitations.respond(a, id, r.version(), true);
  }

  @PostMapping("/api/v1/me/team-invitations/{id}/decline")
  public TeamInvitationResponse decline(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID id,
      @Valid @RequestBody InvitationCommand r) {
    return invitations.respond(a, id, r.version(), false);
  }

  @PostMapping("/api/v1/me/team-invitations/redeem")
  public TeamInvitationResponse redeem(
      @AuthenticationPrincipal AuthenticatedUser a, @Valid @RequestBody RedeemInvitationRequest r) {
    return invitations.redeem(a, r.token());
  }

  @PostMapping("/api/v1/me/team-invitations/preview")
  public TeamInvitationResponse preview(
      @AuthenticationPrincipal AuthenticatedUser a, @Valid @RequestBody RedeemInvitationRequest r) {
    return invitations.preview(a, r.token());
  }
}
