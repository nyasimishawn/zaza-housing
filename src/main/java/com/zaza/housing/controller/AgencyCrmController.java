package com.zaza.housing.controller;

import com.zaza.housing.dto.AgencyAgentResponse;
import com.zaza.housing.dto.AgencyAssignmentRequest;
import com.zaza.housing.dto.AgencyClientLinkRequest;
import com.zaza.housing.dto.AgencyClientRequest;
import com.zaza.housing.dto.AgencyCommandRequest;
import com.zaza.housing.dto.AgencyDashboardResponse;
import com.zaza.housing.dto.AgencyEnquiryResponse;
import com.zaza.housing.dto.AgencyLeadCreateRequest;
import com.zaza.housing.dto.AgencyLeadMutationRequest;
import com.zaza.housing.dto.AgencyPropertyResponse;
import com.zaza.housing.dto.AgencyTaskMutationRequest;
import com.zaza.housing.dto.AgencyTaskRequest;
import com.zaza.housing.dto.AgencyWorkflowChoice;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.model.AgencyClient;
import com.zaza.housing.model.AgencyEvent;
import com.zaza.housing.model.AgencyLead;
import com.zaza.housing.model.AgencyPropertyAssignment;
import com.zaza.housing.model.AgencyTask;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.service.AgencyClientService;
import com.zaza.housing.service.AgencyLeadService;
import com.zaza.housing.service.AgencyTaskService;
import com.zaza.housing.service.AgencyWorkspaceService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organizations/{org}/agency")
public class AgencyCrmController {
  private final AgencyWorkspaceService workspace;
  private final AgencyClientService clients;
  private final AgencyLeadService leads;
  private final AgencyTaskService tasks;

  public AgencyCrmController(
      AgencyWorkspaceService workspace,
      AgencyClientService clients,
      AgencyLeadService leads,
      AgencyTaskService tasks) {
    this.workspace = workspace;
    this.clients = clients;
    this.leads = leads;
    this.tasks = tasks;
  }

  @GetMapping("/enquiries")
  public List<AgencyEnquiryResponse> enquiries(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.enquiries(a, org, limit, offset);
  }

  @GetMapping("/dashboard")
  public AgencyDashboardResponse dashboard(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org) {
    return workspace.dashboard(a, org);
  }

  @GetMapping("/properties")
  public List<AgencyPropertyResponse> properties(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.properties(a, org, limit, offset);
  }

  @GetMapping("/agents")
  public List<AgencyAgentResponse> agents(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.agents(a, org, limit, offset);
  }

  @GetMapping("/assignments")
  public List<AgencyPropertyAssignment> assignments(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.assignments(a, org, limit, offset);
  }

  @PostMapping("/assignments")
  public AgencyPropertyAssignment assign(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @Valid @RequestBody AgencyAssignmentRequest r) {
    return workspace.assign(a, org, r);
  }

  @GetMapping("/clients")
  public List<AgencyClient> clients(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.clients(a, org, q, limit, offset);
  }

  @PostMapping("/clients")
  public AgencyClient createClient(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @Valid @RequestBody AgencyClientRequest r) {
    return clients.save(a, org, null, r);
  }

  @GetMapping("/clients/{id}")
  public AgencyClient client(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org, @PathVariable UUID id) {
    return clients.get(a, org, id);
  }

  @PutMapping("/clients/{id}")
  public AgencyClient editClient(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @Valid @RequestBody AgencyClientRequest r) {
    return clients.save(a, org, id, r);
  }

  @PostMapping("/clients/{id}/registered-customer")
  public AgencyClient linkClient(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @Valid @RequestBody AgencyClientLinkRequest r) {
    return clients.link(a, org, id, r);
  }

  @PostMapping("/clients/{id}/status/{status}")
  public AgencyClient clientStatus(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @PathVariable String status,
      @Valid @RequestBody AgencyCommandRequest r) {
    return clients.status(a, org, id, status, r);
  }

  @GetMapping("/clients/{id}/matches")
  public List<PublicListingResponse> matches(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.matches(a, org, id, limit, offset);
  }

  @GetMapping("/leads")
  public List<AgencyLead> leads(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(required = false) String stage,
      @RequestParam(defaultValue = "false") boolean mine,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.leads(a, org, stage, mine, q, limit, offset);
  }

  @PostMapping("/leads")
  public AgencyLead createLead(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @Valid @RequestBody AgencyLeadCreateRequest r) {
    return leads.create(a, org, r);
  }

  @GetMapping("/leads/{id}")
  public AgencyLead lead(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org, @PathVariable UUID id) {
    return leads.get(a, org, id);
  }

  @GetMapping("/leads/{id}/workflow")
  public List<AgencyWorkflowChoice> workflow(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @RequestParam(required = false) String kind,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.workflow(a, org, id, kind, limit, offset);
  }

  @PostMapping("/leads/{id}/{command}")
  public AgencyLead leadAction(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @PathVariable String command,
      @Valid @RequestBody AgencyLeadMutationRequest r) {
    return leads.mutate(a, org, id, command, r);
  }

  @GetMapping("/tasks")
  public List<AgencyTask> tasks(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @RequestParam(required = false) UUID lead,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String due,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.tasks(a, org, lead, status, due, limit, offset);
  }

  @PostMapping("/tasks")
  public AgencyTask createTask(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @Valid @RequestBody AgencyTaskRequest r) {
    return tasks.create(a, org, r);
  }

  @GetMapping("/tasks/{id}")
  public AgencyTask task(
      @AuthenticationPrincipal AuthenticatedUser a, @PathVariable UUID org, @PathVariable UUID id) {
    return tasks.get(a, org, id);
  }

  @PostMapping("/tasks/{id}/{command}")
  public AgencyTask taskAction(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable UUID id,
      @PathVariable String command,
      @Valid @RequestBody AgencyTaskMutationRequest r) {
    return tasks.mutate(a, org, id, command, r);
  }

  @GetMapping("/history/{kind}/{id}")
  public List<AgencyEvent> history(
      @AuthenticationPrincipal AuthenticatedUser a,
      @PathVariable UUID org,
      @PathVariable String kind,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    return workspace.history(a, org, kind, id, limit, offset);
  }
}
