package com.zaza.housing.service;

import com.zaza.housing.dto.AssignWorkRequest;
import com.zaza.housing.dto.CreateWorkRequest;
import com.zaza.housing.dto.TriageWorkRequest;
import com.zaza.housing.dto.WorkCommandRequest;
import com.zaza.housing.dto.WorkNoteRequest;
import com.zaza.housing.dto.WorkView;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.enums.PropertyStatus;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.WorkAccess;
import com.zaza.housing.model.WorkRequest;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.repository.PropertyRepository;
import com.zaza.housing.repository.WorkRequestRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkRequestService {
  private final WorkAccessService access;
  private final WorkCommandService commands;
  private final WorkActivityService activity;
  private final WorkRequestRepository work;
  private final LeaseAccessService leaseAccess;
  private final LeaseRepository leases;
  private final LeaseService leaseService;
  private final PropertyService properties;
  private final PropertyRepository inventory;
  private final ServiceProviderService providers;
  private final Clock clock;

  public WorkRequestService(
      WorkAccessService access,
      WorkCommandService commands,
      WorkActivityService activity,
      WorkRequestRepository work,
      LeaseAccessService leaseAccess,
      LeaseRepository leases,
      LeaseService leaseService,
      PropertyService properties,
      PropertyRepository inventory,
      ServiceProviderService providers,
      Clock clock) {
    this.access = access;
    this.commands = commands;
    this.activity = activity;
    this.work = work;
    this.leaseAccess = leaseAccess;
    this.leases = leases;
    this.leaseService = leaseService;
    this.properties = properties;
    this.inventory = inventory;
    this.providers = providers;
    this.clock = clock;
  }

  @Transactional
  public WorkView create(
      AuthenticatedUser actor, UUID organization, UUID property, CreateWorkRequest request) {
    boolean tenant = organization == null;
    if(!tenant) access.coordinateProperty(actor,organization,property,true);
    var lease =
        tenant
            ? leaseAccess.tenant(actor, request.leaseId(), true)
            : leases.scoped(property,request.leaseId()).orElseThrow(()->WorkAccessService.missing("Occupancy not found."));
    if(!tenant) access.independent(actor,organization,lease.tenantId());
    if (!tenant && request.kind() != WorkKind.MAINTENANCE)
      throw WorkAccessService.forbidden(
          "Managers create property maintenance; tenant service bookings remain private to the"
              + " customer.");
    var replay = activity.replay(actor, lease.id(), request.requestId(), "CREATED", request);
    var party = tenant ? WorkParty.CUSTOMER : WorkParty.COORDINATOR;
    if (replay.isPresent())
      return commands.response(
          access.get(actor, party, organization, property, replay.get(), false));
    LeaseAccessService.state(
        lease,
        request.kind() == WorkKind.MAINTENANCE
            ? new LeaseStatus[] {LeaseStatus.ACTIVE}
            : new LeaseStatus[] {LeaseStatus.SIGNED, LeaseStatus.ACTIVE});
    var terms = leases.revision(lease.id(), lease.currentRevisionId());
    if (lease.unitReleasedAt() != null
        || !LocalDate.now(clock).isBefore(leaseService.effectiveEnd(lease, terms)))
      throw WorkAccessService.conflict(
          "New requests require a current lease term and unit access.");
    var p = properties.lockForApplication(lease.propertyId());
    if (p.status() != PropertyStatus.ACTIVE)
      throw WorkAccessService.conflict("New work requires an active property.");
    var unit = inventory.unit(lease.propertyId(), lease.unitId()).orElseThrow();
    if (request.kind() == WorkKind.SERVICE_BOOKING
        && (request.offerId() == null
            || request.offerVersion() == null
            || request.offerVersion() < 0))
      throw WorkAccessService.invalid("Choose the current service offer version for a booking.");
    if (request.kind() == WorkKind.MAINTENANCE
        && (request.offerId() != null || request.offerVersion() != null))
      throw WorkAccessService.invalid("Maintenance is triaged before assigning an offer.");
    UUID id =
        work.create(
            lease, actor.id(), request, p.countryCode(), p.city(), p.address(), unit.label());
    if (request.kind() == WorkKind.SERVICE_BOOKING)
      work.assign(
          id,
          providers.dispatch(
              request.offerId(),
              request.offerVersion(),
              request.category(),
              p.countryCode(),
              p.city()));
    var a = new WorkAccess(access.find(id), party, organization);
    activity.record(
        actor,
        a,
        lease.id(),
        request.requestId(),
        "CREATED",
        request,
        "A " + request.kind().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " was submitted.",
        WorkVisibility.SHARED,
        true);
    return commands.response(a);
  }

  @Transactional
  public WorkView triage(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID id,
      TriageWorkRequest request,
      boolean information) {
    String action = information ? "INFORMATION_REQUESTED" : "TRIAGED";
    var m =
        commands.begin(
            actor,
            WorkParty.COORDINATOR,
            org,
            property,
            id,
            request.requestId(),
            action,
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.SUBMITTED, WorkStatus.NEEDS_INFORMATION);
    work.priority(id, request.priority());
    work.state(id, information ? WorkStatus.NEEDS_INFORMATION : WorkStatus.TRIAGED);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        action,
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView respond(AuthenticatedUser actor, UUID id, WorkCommandRequest request) {
    var m =
        commands.begin(
            actor,
            WorkParty.CUSTOMER,
            null,
            null,
            id,
            request.requestId(),
            "INFORMATION_PROVIDED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.NEEDS_INFORMATION);
    work.state(id, WorkStatus.SUBMITTED);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "INFORMATION_PROVIDED",
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView assign(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      AssignWorkRequest request) {
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            "ASSIGNED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    if (a.work().kind() == WorkKind.MAINTENANCE) {
      WorkAccessService.party(a, WorkParty.COORDINATOR);
      WorkAccessService.state(a.work(), WorkStatus.TRIAGED, WorkStatus.ASSIGNED);
    } else {
      WorkAccessService.party(a, WorkParty.CUSTOMER);
      WorkAccessService.state(a.work(), WorkStatus.REJECTED, WorkStatus.ASSIGNED);
    }
    liveSite(a.work());
    work.assign(
        id,
        providers.dispatch(
            request.offerId(),
            request.offerVersion(),
            a.work().category(),
            a.work().countryCode(),
            a.work().city()));
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "ASSIGNED",
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView internal(
      AuthenticatedUser actor, UUID org, UUID property, UUID id, WorkCommandRequest request) {
    var m =
        commands.begin(
            actor,
            WorkParty.COORDINATOR,
            org,
            property,
            id,
            request.requestId(),
            "INTERNAL_ASSIGNED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.TRIAGED);
    liveSite(a.work());
    work.state(id, WorkStatus.ACCEPTED);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "INTERNAL_ASSIGNED",
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView providerDecision(
      AuthenticatedUser actor, UUID org, UUID id, WorkCommandRequest request, boolean accept) {
    String action = accept ? "PROVIDER_ACCEPTED" : "PROVIDER_DECLINED";
    var m =
        commands.begin(
            actor,
            WorkParty.PROVIDER,
            org,
            null,
            id,
            request.requestId(),
            action,
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.ASSIGNED);
    if (accept) {
      liveSite(a.work());
      work.state(id, WorkStatus.ACCEPTED);
    } else work.release(a.work());
    // A declining provider loses access immediately; return only the prior job view with its final
    // decision status.
    if (!accept) {
      activity.record(
          actor,
          a,
          id,
          request.requestId(),
          action,
          request,
          request.message().trim(),
          WorkVisibility.SHARED,
          false);
      return commands.response(a);
    }
    return commands.finish(
        actor,
        a,
        request.requestId(),
        action,
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView cancel(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      WorkCommandRequest request,
      boolean reject) {
    String action = reject ? "REJECTED" : "CANCELLED";
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            action,
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    if (party == WorkParty.PROVIDER)
      throw WorkAccessService.forbidden("Use provider release before starting work.");
    if (reject) {
      WorkAccessService.party(a, WorkParty.COORDINATOR);
      WorkAccessService.state(
          a.work(), WorkStatus.SUBMITTED, WorkStatus.NEEDS_INFORMATION, WorkStatus.TRIAGED);
    } else if (party == WorkParty.CUSTOMER && a.work().kind() == WorkKind.MAINTENANCE)
      WorkAccessService.state(a.work(), WorkStatus.SUBMITTED, WorkStatus.NEEDS_INFORMATION);
    else
      WorkAccessService.state(
          a.work(),
          WorkStatus.SUBMITTED,
          WorkStatus.NEEDS_INFORMATION,
          WorkStatus.TRIAGED,
          WorkStatus.ASSIGNED,
          WorkStatus.ACCEPTED,
          WorkStatus.SCHEDULED,
          WorkStatus.REJECTED);
    work.cancel(id, reject);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        action,
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView release(AuthenticatedUser actor, UUID org, UUID id, WorkCommandRequest request) {
    var m =
        commands.begin(
            actor,
            WorkParty.PROVIDER,
            org,
            null,
            id,
            request.requestId(),
            "PROVIDER_RELEASED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.ACCEPTED, WorkStatus.SCHEDULED);
    work.release(a.work());
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "PROVIDER_RELEASED",
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView note(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      WorkNoteRequest request) {
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            "NOTE_ADDED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    if (request.visibility() == WorkVisibility.INTERNAL && party != WorkParty.COORDINATOR)
      throw WorkAccessService.forbidden("Only property managers can add internal notes.");
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "NOTE_ADDED",
        request,
        request.message().trim(),
        request.visibility());
  }

  public void liveSite(WorkRequest w) {
    var lease = leases.find(w.leaseId()).orElseThrow();
    var p = properties.lockForApplication(w.propertyId());
    if (p.status() != PropertyStatus.ACTIVE || lease.unitReleasedAt() != null)
      throw WorkAccessService.conflict("This property or unit no longer permits work visits.");
    if (w.kind() == WorkKind.SERVICE_BOOKING) {
      LeaseAccessService.state(lease, LeaseStatus.SIGNED, LeaseStatus.ACTIVE);
      if (!LocalDate.now(clock)
          .isBefore(
              leaseService.effectiveEnd(
                  lease, leases.revision(lease.id(), lease.currentRevisionId()))))
        throw WorkAccessService.conflict("The customer's lease term has ended.");
    }
  }
}
