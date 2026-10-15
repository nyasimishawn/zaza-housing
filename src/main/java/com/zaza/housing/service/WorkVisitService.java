package com.zaza.housing.service;

import com.zaza.housing.dto.ConsentWorkRequest;
import com.zaza.housing.dto.CreateServiceQuoteRequest;
import com.zaza.housing.dto.ProposeWorkVisitRequest;
import com.zaza.housing.dto.WorkCommandRequest;
import com.zaza.housing.dto.WorkView;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.enums.WorkVisibility;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.WorkAccess;
import com.zaza.housing.model.WorkRequest;
import com.zaza.housing.repository.LeaseRepository;
import com.zaza.housing.repository.WorkRequestRepository;
import com.zaza.housing.repository.WorkVisitRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkVisitService {
  private final WorkCommandService commands;
  private final WorkAccessService access;
  private final WorkRequestService requests;
  private final WorkRequestRepository work;
  private final WorkVisitRepository visits;
  private final LeaseRepository leases;
  private final LeaseService leaseService;
  private final Clock clock;

  public WorkVisitService(
      WorkCommandService commands,
      WorkAccessService access,
      WorkRequestService requests,
      WorkRequestRepository work,
      WorkVisitRepository visits,
      LeaseRepository leases,
      LeaseService leaseService,
      Clock clock) {
    this.commands = commands;
    this.access = access;
    this.requests = requests;
    this.work = work;
    this.visits = visits;
    this.leases = leases;
    this.leaseService = leaseService;
    this.clock = clock;
  }

  @Transactional
  public WorkView quote(
      AuthenticatedUser actor, UUID org, UUID id, CreateServiceQuoteRequest request) {
    var m =
        commands.begin(
            actor,
            WorkParty.PROVIDER,
            org,
            null,
            id,
            request.requestId(),
            "QUOTE_PROPOSED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.ACCEPTED);
    requests.liveSite(a.work());
    if (!request.validUntil().isAfter(clock.instant())
        || request.validUntil().isAfter(clock.instant().plus(Duration.ofDays(30))))
      throw WorkAccessService.invalid("Quote expiry must be in the next 30 days.");
    String currency = request.currency().toUpperCase(Locale.ROOT);
    ServiceProviderService.currency(currency);
    if (!currency.equals(work.assignedOffer(a.work()).details().currency()))
      throw WorkAccessService.invalid("Quote currency must match the selected offer snapshot.");
    UUID quote = visits.quote(a.work(), request, currency);
    work.quote(id, quote);
    // Quote amounts/scope remain in the restricted quote records, not shared maintenance
    // notes/inbox messages.
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "QUOTE_PROPOSED",
        request,
        "A provider quote awaits authorization by the paying customer.",
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView acceptQuote(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      UUID quoteId,
      ConsentWorkRequest request) {
    var body = Map.of("quoteId", quoteId, "request", request);
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            "QUOTE_ACCEPTED",
            body,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    access.payer(actor, a);
    WorkAccessService.state(a.work(), WorkStatus.ACCEPTED);
    if (!quoteId.equals(a.work().quoteId()))
      throw WorkAccessService.conflict("Review the current quote before authorizing it.");
    var quote =
        visits.quote(id, quoteId).orElseThrow(() -> WorkAccessService.missing("Quote not found."));
    if (quote.status().equals("ACCEPTED") || !quote.validUntil().isAfter(clock.instant()))
      throw WorkAccessService.conflict("This quote is accepted or expired.");
    visits.acceptQuote(quoteId, actor.id(), request.message().trim());
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "QUOTE_ACCEPTED",
        body,
        "The paying customer authorized the current quote.",
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView propose(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      ProposeWorkVisitRequest request) {
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            "VISIT_PROPOSED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    worker(a);
    WorkAccessService.state(a.work(), WorkStatus.ACCEPTED);
    requests.liveSite(a.work());
    if (a.work().providerId() != null && !acceptedQuote(a.work()))
      throw WorkAccessService.conflict(
          "The current quote requires customer authorization before scheduling.");
    Duration length = Duration.between(request.startAt(), request.endAt());
    if (request.startAt().isBefore(clock.instant())
        || request.endAt().isAfter(clock.instant().plus(Duration.ofDays(90)))
        || length.compareTo(Duration.ofMinutes(15)) < 0
        || length.compareTo(Duration.ofDays(1)) > 0)
      throw WorkAccessService.invalid(
          "Use a future visit of 15 minutes to 24 hours within the next 90 days.");
    if (a.work().kind() == WorkKind.SERVICE_BOOKING) {
      var lease = leases.find(a.work().leaseId()).orElseThrow();
      var revision = leases.revision(lease.id(), lease.currentRevisionId());
      var start = revision.terms().startDate().atStartOfDay(ZoneOffset.UTC).toInstant();
      var end = leaseService.effectiveEnd(lease, revision).atStartOfDay(ZoneOffset.UTC).toInstant();
      if (request.startAt().isBefore(start) || request.endAt().isAfter(end))
        throw WorkAccessService.conflict(
            "A personal service visit must fit inside the agreed lease term.");
    }
    UUID visit = visits.propose(a.work(), request);
    work.visit(id, visit);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "VISIT_PROPOSED",
        request,
        "A proposed visit requires the occupant's explicit access consent.",
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView accept(
      AuthenticatedUser actor, UUID id, UUID visitId, ConsentWorkRequest request) {
    var body = Map.of("visitId", visitId, "request", request);
    var m =
        commands.begin(
            actor,
            WorkParty.CUSTOMER,
            null,
            null,
            id,
            request.requestId(),
            "VISIT_ACCEPTED",
            body,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.ACCEPTED);
    requests.liveSite(a.work());
    if (a.work().providerId() != null && !acceptedQuote(a.work()))
      throw WorkAccessService.conflict("Authorize the current quote before accepting a visit.");
    access.providerLock(a.work());
    if (!visitId.equals(a.work().visitId()))
      throw WorkAccessService.conflict("Consent to the current visit proposal.");
    var visit =
        visits.visit(id, visitId).orElseThrow(() -> WorkAccessService.missing("Visit not found."));
    if (visit.startAt().isBefore(clock.instant()) || visit.status().equals("ACCEPTED"))
      throw WorkAccessService.conflict("This visit has passed or was already accepted.");
    if (a.work().providerId() != null
        && visits.overlaps(a.work().providerId(), id, visit.startAt(), visit.endAt()))
      throw WorkAccessService.conflict(
          "The provider is already committed during this visit. Request another slot.");
    visits.acceptVisit(visitId, actor.id(), request.message().trim());
    work.state(id, WorkStatus.SCHEDULED);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "VISIT_ACCEPTED",
        body,
        "The occupant consented to this visit and access arrangement.",
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView reschedule(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      WorkCommandRequest request) {
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            "RESCHEDULE_REQUESTED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    WorkAccessService.state(a.work(), WorkStatus.SCHEDULED);
    access.providerLock(a.work());
    work.visit(id, null);
    work.state(id, WorkStatus.ACCEPTED);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "RESCHEDULE_REQUESTED",
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  @Transactional
  public WorkView start(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID id,
      WorkCommandRequest request) {
    var m =
        commands.begin(
            actor,
            party,
            org,
            property,
            id,
            request.requestId(),
            "STARTED",
            request,
            request.version());
    var a = m.access();
    if (m.replay()) return commands.response(a);
    worker(a);
    WorkAccessService.state(a.work(), WorkStatus.SCHEDULED);
    requests.liveSite(a.work());
    var visit = visits.visit(id, a.work().visitId()).orElseThrow();
    if (clock.instant().isBefore(visit.startAt()) || !clock.instant().isBefore(visit.endAt()))
      throw WorkAccessService.conflict(
          "Start during the accepted visit window; reschedule a missed visit.");
    work.state(id, WorkStatus.IN_PROGRESS);
    return commands.finish(
        actor,
        a,
        request.requestId(),
        "STARTED",
        request,
        request.message().trim(),
        WorkVisibility.SHARED);
  }

  private boolean acceptedQuote(WorkRequest w) {
    return w.quoteId() != null
        && visits.quote(w.id(), w.quoteId()).orElseThrow().status().equals("ACCEPTED");
  }

  public static void worker(WorkAccess a) {
    if (a.work().providerId() != null) WorkAccessService.party(a, WorkParty.PROVIDER);
    else WorkAccessService.party(a, WorkParty.COORDINATOR);
  }
}
