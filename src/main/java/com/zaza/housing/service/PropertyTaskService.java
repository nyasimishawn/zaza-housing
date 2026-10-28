package com.zaza.housing.service;

import com.zaza.housing.dto.PropertyTaskActionRequest;
import com.zaza.housing.dto.PropertyTaskCreateRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.CaretakerEvent;
import com.zaza.housing.model.PropertyTask;
import com.zaza.housing.repository.CaretakerRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyTaskService {
  private final CaretakerAccessService access;
  private final CaretakerRepository repository;
  private final CaretakerActivityService activity;
  private final Clock clock;

  public PropertyTaskService(
      CaretakerAccessService access,
      CaretakerRepository repository,
      CaretakerActivityService activity,
      Clock clock) {
    this.access = access;
    this.repository = repository;
    this.activity = activity;
    this.clock = clock;
  }

  public List<PropertyTask> list(
      AuthenticatedUser a, UUID o, UUID p, String status, UUID assignee, int l, int f) {
    access.organization(a, o, false);
    Pagination.validate(l, f);
    if (status != null
        && !Set.of("OPEN", "IN_PROGRESS", "BLOCKED", "SUBMITTED", "COMPLETED", "CANCELLED")
            .contains(status)) throw AgencyAccessService.bad("Choose a valid task status.");
    return repository.tasks(o, a.id(), p, status, assignee, l, f);
  }

  public PropertyTask get(AuthenticatedUser a, UUID o, UUID id) {
    return access.task(a, o, id, false);
  }

  public List<CaretakerEvent> history(AuthenticatedUser a, UUID o, UUID id, int l, int f) {
    get(a, o, id);
    Pagination.validate(l, f);
    return repository.history("TASK", id, l, f);
  }

  private void due(java.time.Instant due) {
    if (due == null
        || !repository.future(due)
        || due.isAfter(clock.instant().plus(730, ChronoUnit.DAYS)))
      throw AgencyAccessService.bad("Choose a future due time within two years.");
  }

  @Transactional
  public PropertyTask create(AuthenticatedUser a, UUID o, PropertyTaskCreateRequest r) {
    access.organization(a, o, true);
    var p = access.property(a, o, r.propertyId(), "OPERATIONS", true);
    access.active(p);
    UUID replay = activity.replay(a, o, r.requestId(), "PROPERTY_TASK_CREATED", null, r);
    if (replay != null) return get(a, o, replay);
    due(r.dueAt());
    var member = access.assignee(a, o, p.id(), r.assigneeId());
    String location =
        r.unitId() == null
            ? r.locationLabel()
            : repository
                .unit(p.id(), r.unitId())
                .orElseThrow(
                    () -> AgencyAccessService.bad("Choose an active unit on this property."))
                .label();
    if (location == null || location.isBlank())
      throw AgencyAccessService.bad("Describe the common area or work location.");
    UUID id = UUID.randomUUID();
    repository.createTask(id, o, a.id(), p, location.trim(), member, r, clock.instant());
    activity.record(
        a,
        o,
        r.requestId(),
        p.id(),
        "PROPERTY_TASK_CREATED",
        "TASK",
        id,
        r,
        "Task created.",
        member.id(),
        o);
    return get(a, o, id);
  }

  @Transactional
  public PropertyTask command(
      AuthenticatedUser a, UUID o, UUID id, String command, PropertyTaskActionRequest r) {
    var org = access.organization(a, o, true);
    var t = access.task(a, o, id, true);
    var p = access.property(a, o, t.propertyId(), "OPERATIONS", false);
    String action = "PROPERTY_TASK_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(a, o, r.requestId(), action, id, r) != null) return get(a, o, id);
    AgencyAccessService.version(t.version(), r.version());
    boolean manager = access.manager(org);
    if (!command.equals("comment")) {
      access.active(p);
      if (Set.of("COMPLETED", "CANCELLED").contains(t.status()))
        throw AgencyAccessService.conflict("This task is closed.");
    }
    String next = null;
    switch (command) {
      case "start" -> {
        state(t, "OPEN", "BLOCKED");
        next = "IN_PROGRESS";
      }
      case "block" -> {
        state(t, "OPEN", "IN_PROGRESS");
        message(r.message(), 10);
        next = "BLOCKED";
      }
      case "submit" -> {
        state(t, "IN_PROGRESS", "BLOCKED");
        message(r.message(), 20);
        next = "SUBMITTED";
      }
      case "approve" -> {
        access.requireManager(org);
        state(t, "SUBMITTED");
        next = "COMPLETED";
      }
      case "rework" -> {
        access.requireManager(org);
        state(t, "SUBMITTED");
        message(r.message(), 10);
        next = "IN_PROGRESS";
      }
      case "cancel" -> {
        access.requireManager(org);
        message(r.message(), 10);
        next = "CANCELLED";
      }
      case "assign" -> {
        access.requireManager(org);
        state(t, "OPEN", "IN_PROGRESS", "BLOCKED");
        if (r.assigneeId() == null) throw AgencyAccessService.bad("Choose an assignee.");
        repository.taskAssign(
            id, o, access.assignee(a, o, p.id(), r.assigneeId()), clock.instant());
      }
      case "reschedule" -> {
        access.requireManager(org);
        state(t, "OPEN", "IN_PROGRESS", "BLOCKED");
        due(r.dueAt());
        message(r.message(), 10);
        repository.taskDue(id, r.dueAt(), clock.instant());
      }
      case "comment" -> {
        message(r.message(), 1);
        if (!Set.of("COMPLETED", "CANCELLED").contains(t.status()))
          repository.taskTouch(id, clock.instant());
      }
      default -> throw AgencyAccessService.bad("Unknown task action.");
    }
    if (next != null)
      repository.taskStatus(id, next, r.message(), manager ? a.id() : null, clock.instant());
    var current = repository.task(o, a.id(), id, false).orElse(t);
    activity.record(
        a,
        o,
        r.requestId(),
        t.propertyId(),
        action,
        "TASK",
        id,
        r,
        r.message(),
        current.assigneeId(),
        current.assigneeOrganizationId());
    return get(a, o, id);
  }

  private void state(PropertyTask t, String... allowed) {
    if (!Arrays.asList(allowed).contains(t.status()))
      throw AgencyAccessService.conflict("This action is unavailable in the current task state.");
  }

  private void message(String value, int min) {
    if (value == null || value.trim().length() < min)
      throw AgencyAccessService.bad(
          "Add a meaningful explanation of at least " + min + " characters.");
  }
}
