package com.zaza.housing.controller;

import com.zaza.housing.dto.AgencyMandateCommand;
import com.zaza.housing.dto.AgencyMandateRequest;
import com.zaza.housing.dto.AgencyPropertyResponse;
import com.zaza.housing.model.AgencyEvent;
import com.zaza.housing.model.AgencyMandate;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.AgencyMandateService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
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
@RequestMapping("/api/v1/organizations/{org}/agency/mandates")
public class AgencyMandateController {
  private final AgencyMandateService service;

  public AgencyMandateController(AgencyMandateService service) {
    this.service = service;
  }

  @GetMapping
  public List<AgencyMandate> list(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.list(a, org, limit, offset);
  }

  @GetMapping("/agencies")
  public List<Map<String, Object>> agencies(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam String q,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.agencies(a, org, q, limit, offset);
  }

  @GetMapping("/properties")
  public List<AgencyPropertyResponse> properties(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.properties(a, org, limit, offset);
  }

  @GetMapping("/{id}")
  public AgencyMandate get(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org, @PathVariable UUID id) {
    return service.get(a, org, id);
  }

  @GetMapping("/{id}/history")
  public List<AgencyEvent> history(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return service.history(a, org, id, limit, offset);
  }

  @PostMapping
  public AgencyMandate create(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @Valid @RequestBody AgencyMandateRequest r) {
    return service.create(a, org, r);
  }

  @PostMapping("/{id}/{command}")
  public AgencyMandate command(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @PathVariable String command,
      @Valid @RequestBody AgencyMandateCommand r) {
    return service.command(a, org, id, command, r);
  }
}
