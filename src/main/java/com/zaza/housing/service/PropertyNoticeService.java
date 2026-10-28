package com.zaza.housing.service;

import com.zaza.housing.dto.PropertyNoticeCommand;
import com.zaza.housing.dto.PropertyNoticeCreateRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.CaretakerEvent;
import com.zaza.housing.model.PropertyNotice;
import com.zaza.housing.repository.CaretakerRepository;
import com.zaza.housing.repository.OrganizationRepository;
import com.zaza.housing.repository.PropertyNoticeRepository;
import com.zaza.housing.util.Pagination;
import java.time.*;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PropertyNoticeService {
  private final CaretakerAccessService access;
  private final CaretakerOccupancyService occupancy;
  private final PropertyNoticeRepository repository;
  private final CaretakerRepository caretaker;
  private final OrganizationRepository memberships;
  private final PropertyService properties;
  private final CaretakerActivityService activity;
  private final Clock clock;

  public PropertyNoticeService(
      CaretakerAccessService access,
      CaretakerOccupancyService occupancy,
      PropertyNoticeRepository repository,
      CaretakerRepository caretaker,
      OrganizationRepository memberships,
      PropertyService properties,
      CaretakerActivityService activity,
      Clock clock) {
    this.access = access;
    this.occupancy = occupancy;
    this.repository = repository;
    this.caretaker = caretaker;
    this.memberships = memberships;
    this.properties = properties;
    this.activity = activity;
    this.clock = clock;
  }

  public PropertyNotice get(AuthenticatedUser a, UUID o, UUID p, UUID id, boolean lock) {
    var n = repository.find(id, false).orElseThrow(CaretakerAccessService::missing);
    if (o == null) {
      if (!n.tenantId().equals(a.id()) || n.publishedAt() == null)
        throw CaretakerAccessService.missing();
      if (lock) {
        memberships.lock(n.ownerOrganizationId());
        properties.lockForApplication(n.propertyId());
      }
    } else {
      access.property(a, o, p, "NOTICES", lock);
      if (!n.propertyId().equals(p)) throw CaretakerAccessService.missing();
    }
    return repository.find(id, lock).orElseThrow(CaretakerAccessService::missing);
  }

  public List<PropertyNotice> list(AuthenticatedUser a, UUID o, UUID p, int l, int f) {
    if (o != null) access.property(a, o, p, "NOTICES", false);
    Pagination.validate(l, f);
    return repository.list(o, p, a.id(), o == null, l, f);
  }

  public List<CaretakerEvent> history(AuthenticatedUser a, UUID o, UUID p, UUID id, int l, int f) {
    get(a, o, p, id, false);
    Pagination.validate(l, f);
    return caretaker.history("NOTICE", id, l, f);
  }

  private void window(PropertyNoticeCreateRequest r) {
    if ((r.startsAt() == null) != (r.endsAt() == null)
        || r.kind().equals("INSPECTION") && r.startsAt() == null)
      throw AgencyAccessService.bad("Inspection notices need a start and end time.");
    if (r.startsAt() != null) {
      long minutes = Duration.between(r.startsAt(), r.endsAt()).toMinutes();
      if (!caretaker.future(r.startsAt())
          || r.startsAt().isAfter(clock.instant().plus(Duration.ofDays(90)))
          || minutes < 15
          || minutes > 120)
        throw AgencyAccessService.bad(
            "Choose a future appointment within 90 days, lasting 15–120 minutes.");
    }
  }

  @Transactional
  public PropertyNotice create(AuthenticatedUser a, UUID o, UUID p, PropertyNoticeCreateRequest r) {
    access.organization(a, o, true);
    var property = access.property(a, o, p, "NOTICES", true);
    access.active(property);
    UUID replay = activity.replay(a, o, r.requestId(), "PROPERTY_NOTICE_CREATED", null, r);
    if (replay != null) return get(a, o, p, replay, false);
    var lease = occupancy.lease(a, o, p, r.leaseId(), "NOTICES", false);
    if (lease.unitReleasedAt() != null
        || !Set.of("SIGNED", "ACTIVE").contains(lease.status().name()))
      throw AgencyAccessService.conflict("Create notices for an ongoing occupancy only.");
    if (a.id().equals(lease.tenantId()))
      throw WorkAccessService.forbidden(
          "Another authorized person must prepare your own tenancy notice.");
    window(r);
    UUID id = UUID.randomUUID();
    repository.create(
        id, a.id(), o, occupancy.response(lease), property, lease.tenantId(), r, clock.instant());
    activity.record(
        a,
        o,
        r.requestId(),
        p,
        "PROPERTY_NOTICE_CREATED",
        "NOTICE",
        id,
        r,
        "Operational notice drafted for manager review.",
        null,
        null);
    return get(a, o, p, id, false);
  }

  @Transactional
  public PropertyNotice command(
      AuthenticatedUser a, UUID o, UUID p, UUID id, String command, PropertyNoticeCommand r) {
    var n = get(a, o, p, id, true);
    boolean tenant = o == null;
    UUID scope = tenant ? n.ownerOrganizationId() : o;
    String action = "PROPERTY_NOTICE_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(a, scope, r.requestId(), action, id, r) != null)
      return get(a, o, p, id, false);
    AgencyAccessService.version(n.version(), r.version());
    if (!Boolean.TRUE.equals(r.consent()))
      throw AgencyAccessService.bad("Confirm the exact notice and your response.");
    if (n.status().equals("CANCELLED"))
      throw AgencyAccessService.conflict("This notice is cancelled; its history is retained.");
    if (!tenant) {
      if (!command.equals("reply")) access.requireManager(access.organization(a, o, false));
      if (n.tenantId().equals(a.id()))
        throw WorkAccessService.forbidden("Another manager must publish your tenancy notice.");
      switch (command) {
        case "publish" -> {
          access.active(access.property(a, o, p, "NOTICES", false));
          if (!n.status().equals("DRAFT"))
            throw AgencyAccessService.conflict("This notice is already published.");
          var lease = occupancy.lease(a, o, p, n.leaseId(), "NOTICES", false);
          if (lease.unitReleasedAt() != null
              || !Set.of("SIGNED", "ACTIVE").contains(lease.status().name()))
            throw AgencyAccessService.conflict("This occupancy has ended.");
          if (!caretaker.allowed(n.createdByOrganizationId(), p, n.createdBy(), "NOTICES"))
            throw AgencyAccessService.conflict(
                "The author's notice assignment ended. Prepare a new notice.");
          window(
              new PropertyNoticeCreateRequest(
                  r.requestId(),
                  n.leaseId(),
                  n.kind(),
                  n.title(),
                  n.message(),
                  n.startsAt(),
                  n.endsAt()));
          repository.publish(id, a.id(), clock.instant());
        }
        case "cancel" -> repository.cancel(id, clock.instant());
        case "reply" -> {
          if (!n.status().equals("PUBLISHED"))
            throw AgencyAccessService.conflict(
                "Publish the notice before sending a resident reply.");
          repository.reply(id, null, clock.instant());
        }
        default ->
            throw AgencyAccessService.bad(
                "Managers publish or cancel notices; tenants respond independently.");
      }
    } else {
      if (!n.status().equals("PUBLISHED")) throw CaretakerAccessService.missing();
      if (!Set.of("acknowledge", "accept", "decline", "reschedule", "reply").contains(command))
        throw WorkAccessService.forbidden("Only a manager can publish or cancel this notice.");
      if (command.equals("accept") && (n.startsAt() == null || !caretaker.future(n.startsAt())))
        throw AgencyAccessService.conflict(
            "Accept a future appointment only; acknowledge general notices.");
      repository.reply(
          id, command.equals("reply") ? null : command.toUpperCase(Locale.ROOT), clock.instant());
    }
    activity.record(
        a, scope, r.requestId(), n.propertyId(), action, "NOTICE", id, r, r.message(), null, null);
    if (!tenant) repository.notifyTenant(id);
    return get(a, o, p, id, false);
  }
}
