package com.zaza.housing.service;

import com.zaza.housing.dto.ProposeViewingRequest;
import com.zaza.housing.dto.ViewingCommandRequest;
import com.zaza.housing.dto.ViewingHistoryResponse;
import com.zaza.housing.dto.ViewingResponse;
import com.zaza.housing.enums.ConversationKind;
import com.zaza.housing.enums.ConversationRole;
import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Conversation;
import com.zaza.housing.model.ConversationAccess;
import com.zaza.housing.model.ViewingAppointment;
import com.zaza.housing.model.ViewingProposal;
import com.zaza.housing.repository.ViewingRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ViewingService {
  private final ViewingRepository viewings;
  private final ConversationAccessService access;
  private final ConversationActivityService activity;
  private final ListingService listings;
  private final PropertyService properties;
  private final OrganizationService organizations;
  private final Clock clock;

  public ViewingService(
      ViewingRepository viewings,
      ConversationAccessService access,
      ConversationActivityService activity,
      ListingService listings,
      PropertyService properties,
      OrganizationService organizations,
      Clock clock) {
    this.viewings = viewings;
    this.access = access;
    this.activity = activity;
    this.listings = listings;
    this.properties = properties;
    this.organizations = organizations;
    this.clock = clock;
  }

  private ConversationAccess enquiry(AuthenticatedUser actor, UUID id, boolean lock) {
    var a = access.get(actor, id, lock);
    if (a.conversation().kind() != ConversationKind.ENQUIRY)
      throw WorkAccessService.invalid("Viewings belong to listing enquiries.");
    return a;
  }

  private ViewingAppointment find(UUID conversation, UUID id) {
    return viewings
        .find(conversation, id)
        .orElseThrow(() -> WorkAccessService.missing("Viewing not found."));
  }

  private void version(ViewingAppointment v, long version) {
    if (v.version() != version)
      throw WorkAccessService.conflict("The viewing changed. Reload before continuing.");
  }

  private void state(ViewingAppointment v, String... states) {
    if (!Arrays.asList(states).contains(v.status()))
      throw WorkAccessService.conflict("This viewing is " + v.status() + ".");
  }

  private void times(Instant start, Instant end) {
    Duration duration = Duration.between(start, end);
    if (!start.isAfter(clock.instant())
        || end.isAfter(clock.instant().plus(Duration.ofDays(90)))
        || duration.compareTo(Duration.ofMinutes(15)) < 0
        || duration.compareTo(Duration.ofHours(2)) > 0)
      throw WorkAccessService.invalid(
          "Propose a future viewing lasting 15 minutes to 2 hours within the next 90 days.");
  }

  private void listing(Conversation c, long version) {
    var source = listings.applicationSource(c.listingId());
    if (source.version() != version)
      throw WorkAccessService.conflict(
          "The published listing changed. Propose a new viewing after reviewing it.");
  }

  public ViewingResponse get(AuthenticatedUser actor, UUID conversation, UUID id) {
    enquiry(actor, conversation, false);
    return viewings
        .response(actor.id(), conversation, id)
        .orElseThrow(() -> WorkAccessService.missing("Viewing not found."));
  }

  public List<ViewingHistoryResponse> history(
      AuthenticatedUser actor, UUID conversation, UUID id, int limit, int offset) {
    Pagination.validate(limit, offset);
    enquiry(actor, conversation, false);
    find(conversation, id);
    return viewings.history(id, limit, offset);
  }

  public List<ViewingResponse> list(
      AuthenticatedUser actor,
      UUID org,
      UUID property,
      UUID conversation,
      String status,
      Instant from,
      Instant to,
      int limit,
      int offset) {
    Pagination.validate(limit, offset);
    if (org != null && organizations.get(actor, org).role() == MembershipRole.MEMBER)
      throw WorkAccessService.forbidden("Organization management permission is required.");
    if (property != null) properties.getForManagement(actor, org, property);
    if (conversation != null) enquiry(actor, conversation, false);
    if (status != null
        && !Set.of(
                "PENDING",
                "CONFIRMED",
                "RESCHEDULE_PENDING",
                "DECLINED",
                "CANCELLED",
                "COMPLETED",
                "NO_SHOW")
            .contains(status)) throw WorkAccessService.invalid("Unknown viewing status.");
    if ((from == null) != (to == null)
        || from != null
            && (!to.isAfter(from) || Duration.between(from, to).compareTo(Duration.ofDays(31)) > 0))
      throw WorkAccessService.invalid(
          "Specify both calendar bounds with a positive range of at most 31 days.");
    return viewings.list(actor.id(), org, property, conversation, status, from, to, limit, offset);
  }

  @Transactional
  public ViewingResponse create(
      AuthenticatedUser actor, UUID conversation, ProposeViewingRequest r) {
    var a = enquiry(actor, conversation, true);
    var old = activity.replay(actor, conversation, r.requestId(), "VIEWING_PROPOSED", r);
    if (old.isPresent()) return get(actor, conversation, old.get());
    access.version(a.conversation(), r.version());
    access.open(a.conversation());
    times(r.startAt(), r.endAt());
    listing(a.conversation(), r.listingVersion());
    viewings.lockCustomer(a.conversation().customerId());
    if (viewings.active(conversation))
      throw WorkAccessService.conflict("Reschedule or cancel the existing active viewing first.");
    UUID id = viewings.create(conversation, clock.instant());
    var v = find(conversation, id);
    UUID proposal = viewings.propose(v, actor.id(), a.role(), r, clock.instant());
    finish(actor, a, v, r.requestId(), "VIEWING_PROPOSED", r, proposal, r.reason());
    return get(actor, conversation, id);
  }

  @Transactional
  public ViewingResponse reschedule(
      AuthenticatedUser actor, UUID conversation, UUID id, ProposeViewingRequest r) {
    var a = enquiry(actor, conversation, true);
    var body = Map.of("viewingId", id, "request", r);
    if (activity
        .replay(actor, conversation, r.requestId(), "VIEWING_RESCHEDULED", body)
        .isPresent()) return get(actor, conversation, id);
    var v = find(conversation, id);
    version(v, r.version());
    access.open(a.conversation());
    state(v, "PENDING", "CONFIRMED", "RESCHEDULE_PENDING");
    times(r.startAt(), r.endAt());
    listing(a.conversation(), r.listingVersion());
    viewings.lockCustomer(a.conversation().customerId());
    if (v.confirmedProposalId() != null
        && !viewings.proposal(v.confirmedProposalId()).endAt().isAfter(clock.instant()))
      throw WorkAccessService.conflict(
          "Record the completed viewing outcome before arranging another visit.");
    UUID proposal = viewings.propose(v, actor.id(), a.role(), r, clock.instant());
    finish(actor, a, v, r.requestId(), "VIEWING_RESCHEDULED", body, proposal, r.reason());
    return get(actor, conversation, id);
  }

  @Transactional
  public ViewingResponse decision(
      AuthenticatedUser actor, UUID conversation, UUID id, ViewingCommandRequest r, String action) {
    var a = enquiry(actor, conversation, true);
    var body = Map.of("viewingId", id, "request", r);
    if (activity.replay(actor, conversation, r.requestId(), "VIEWING_" + action, body).isPresent())
      return get(actor, conversation, id);
    var v = find(conversation, id);
    version(v, r.version());
    viewings.lockCustomer(a.conversation().customerId());
    var q = viewings.proposal(v.currentProposalId());
    switch (action) {
      case "CONFIRMED" -> {
        access.open(a.conversation());
        state(v, "PENDING", "RESCHEDULE_PENDING");
        counterparty(a, q);
        times(q.startAt(), q.endAt());
        listing(a.conversation(), q.listingVersion());
        if (viewings.overlap(
            id, a.conversation().unitId(), a.conversation().customerId(), q.startAt(), q.endAt()))
          throw WorkAccessService.conflict(
              "The unit or visitor already has a confirmed viewing during this interval.");
        viewings.confirm(id, q.id());
      }
      case "DECLINED" -> {
        state(v, "PENDING", "RESCHEDULE_PENDING");
        counterparty(a, q);
        viewings.decline(v);
      }
      case "CANCELLED" -> {
        state(v, "PENDING", "CONFIRMED", "RESCHEDULE_PENDING");
        if (v.confirmedProposalId() != null
            && !viewings.proposal(v.confirmedProposalId()).endAt().isAfter(clock.instant()))
          throw WorkAccessService.conflict(
              "Record a viewing outcome instead of cancelling a past confirmed visit.");
        viewings.state(id, "CANCELLED");
      }
      case "COMPLETED", "NO_SHOW" -> {
        state(v, "CONFIRMED", "RESCHEDULE_PENDING");
        if (a.role() != ConversationRole.MANAGER)
          throw WorkAccessService.forbidden("Only property managers can record viewing outcomes.");
        if (clock.instant().isBefore(viewings.proposal(v.confirmedProposalId()).endAt()))
          throw WorkAccessService.conflict(
              "Wait until the confirmed appointment has ended before recording its outcome.");
        viewings.state(id, action);
      }
      default -> throw WorkAccessService.invalid("Unknown viewing decision.");
    }
    finish(actor, a, v, r.requestId(), "VIEWING_" + action, body, q.id(), r.reason());
    return get(actor, conversation, id);
  }

  private void counterparty(ConversationAccess a, ViewingProposal q) {
    if (a.role() == q.proposedRole())
      throw WorkAccessService.forbidden("The other party must accept or decline this proposal.");
  }

  private void finish(
      AuthenticatedUser actor,
      ConversationAccess a,
      ViewingAppointment v,
      UUID request,
      String action,
      Object body,
      UUID proposal,
      String reason) {
    viewings.history(
        v.id(),
        proposal,
        actor.id(),
        a.role(),
        action,
        ConversationService.text(reason),
        clock.instant());
    viewings.touch(v.id());
    activity.finish(actor, a, request, action, body, v.id(), true);
  }
}
