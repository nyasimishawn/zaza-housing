package com.zaza.housing.service;

import com.zaza.housing.dto.AgencyTaskMutationRequest;
import com.zaza.housing.dto.AgencyTaskRequest;
import com.zaza.housing.model.AgencyTask;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AgencyRepository;
import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgencyTaskService {
  private final AgencyAccessService access;
  private final AgencyRepository repository;
  private final AgencyActivityService activity;
  private final Clock clock;

  public AgencyTaskService(
      AgencyAccessService access,
      AgencyRepository repository,
      AgencyActivityService activity,
      Clock clock) {
    this.access = access;
    this.repository = repository;
    this.activity = activity;
    this.clock = clock;
  }

  public AgencyTask get(AuthenticatedUser actor, UUID org, UUID id) {
    access.get(actor, org, false, false);
    return repository.task(org, actor.id(), id, false).orElseThrow(AgencyAccessService::missing);
  }

  @Transactional
  public AgencyTask create(AuthenticatedUser actor, UUID org, AgencyTaskRequest r) {
    var o = access.get(actor, org, true, false);
    var l = access.lead(actor, org, r.leadId(), true);
    access.agent(org, r.assigneeId());
    if (!access.manages(o) && !actor.id().equals(r.assigneeId()))
      throw AgencyAccessService.bad("Agents can schedule only their own follow-ups.");
    if (!access.manages(o) && !Objects.equals(l.assignedTo(), r.assigneeId()))
      throw AgencyAccessService.missing();
    if (repository.lead(org, r.assigneeId(), l.id(), false).isEmpty())
      throw AgencyAccessService.bad("The task assignee must have access to this lead.");
    UUID replay = activity.replay(actor, org, r.requestId(), "AGENCY_TASK_CREATED", null, r);
    if (replay != null) return get(actor, org, replay);
    validate(r.kind(), r.dueAt());
    UUID id = repository.createTask(org, r, clock.instant());
    activity.record(
        actor, org, r.requestId(), "AGENCY_TASK_CREATED", "TASK", id, r, r.description());
    return get(actor, org, id);
  }

  @Transactional
  public AgencyTask mutate(
      AuthenticatedUser actor, UUID org, UUID id, String command, AgencyTaskMutationRequest r) {
    access.get(actor, org, true, false);
    var initial = get(actor, org, id);
    access.lead(actor, org, initial.leadId(), true);
    var t = repository.task(org, actor.id(), id, true).orElseThrow(AgencyAccessService::missing);
    String action = "AGENCY_TASK_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(actor, org, r.requestId(), action, id, r) != null)
      return get(actor, org, id);
    AgencyAccessService.version(t.version(), r.version());
    if (!t.status().equals("OPEN"))
      throw AgencyAccessService.conflict("This follow-up is already closed.");
    String status =
        switch (command) {
          case "complete" -> "DONE";
          case "cancel" -> "CANCELLED";
          case "reschedule" -> "OPEN";
          default -> throw AgencyAccessService.bad("Unknown task action.");
        };
    if (command.equals("reschedule")) validate(t.kind(), r.dueAt());
    repository.taskState(
        id, status, command.equals("reschedule") ? r.dueAt() : null, clock.instant());
    activity.record(actor, org, r.requestId(), action, "TASK", id, r, r.message());
    return get(actor, org, id);
  }

  private void validate(String kind, java.time.Instant due) {
    if (!Set.of("CALL", "EMAIL", "FOLLOW_UP", "VIEWING", "OTHER").contains(kind)
        || due == null
        || due.isAfter(clock.instant().plusSeconds(366L * 86400)))
      throw AgencyAccessService.bad("Choose a task kind and a due date within the next year.");
  }
}
