package com.zaza.housing.controller;

import com.zaza.housing.dto.CaretakerDashboardResponse;
import com.zaza.housing.dto.CaretakerGrantCommand;
import com.zaza.housing.dto.CaretakerInvitationRequest;
import com.zaza.housing.dto.CaretakerMemberResponse;
import com.zaza.housing.dto.CaretakerPropertyResponse;
import com.zaza.housing.dto.CaretakerUnitResponse;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.CaretakerEvent;
import com.zaza.housing.model.CaretakerGrant;
import com.zaza.housing.service.CaretakerGrantService;
import com.zaza.housing.service.CaretakerWorkspaceService;
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
@RequestMapping("/api/v1/organizations/{org}/caretaker")
public class CaretakerWorkspaceController {
  private final CaretakerWorkspaceService workspace;
  private final CaretakerGrantService grants;

  public CaretakerWorkspaceController(
      CaretakerWorkspaceService workspace, CaretakerGrantService grants) {
    this.workspace = workspace;
    this.grants = grants;
  }

  @GetMapping("/dashboard")
  public CaretakerDashboardResponse dashboard(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org) {
    return workspace.dashboard(a, org);
  }

  @GetMapping("/properties/{property}")
  public CaretakerPropertyResponse property(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID property) {
    return workspace.property(a, org, property);
  }

  @GetMapping("/properties")
  public List<CaretakerPropertyResponse> properties(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.properties(a, org, limit, offset);
  }

  @GetMapping("/members")
  public List<CaretakerMemberResponse> members(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(defaultValue = "false") boolean eligible,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.members(a, org, propertyId, eligible, limit, offset);
  }

  @GetMapping("/properties/{property}/units")
  public List<CaretakerUnitResponse> units(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID property,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.units(a, org, property, limit, offset);
  }

  @GetMapping("/assignments")
  public List<CaretakerGrant> list(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(required = false) UUID propertyId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return grants.list(a, org, propertyId, limit, offset);
  }

  @PostMapping("/assignments")
  public CaretakerGrant invite(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestBody @Valid CaretakerInvitationRequest r) {
    return grants.invite(a, org, r);
  }

  @GetMapping("/assignments/{id}")
  public CaretakerGrant get(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org, @PathVariable UUID id) {
    return grants.get(a, org, id);
  }

  @GetMapping("/assignments/{id}/history")
  public List<CaretakerEvent> history(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return grants.history(a, org, id, limit, offset);
  }

  @PostMapping("/assignments/{id}/{command}")
  public CaretakerGrant command(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @PathVariable String command,
      @RequestBody @Valid CaretakerGrantCommand r) {
    return grants.command(a, org, id, command, r);
  }
}
