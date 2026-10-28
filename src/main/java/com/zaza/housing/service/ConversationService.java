package com.zaza.housing.service;

import com.zaza.housing.dto.ConversationCommandRequest;
import com.zaza.housing.dto.ConversationEventResponse;
import com.zaza.housing.dto.ConversationInboxSummaryResponse;
import com.zaza.housing.dto.ConversationMessageResponse;
import com.zaza.housing.dto.ConversationPreferenceRequest;
import com.zaza.housing.dto.ConversationResponse;
import com.zaza.housing.dto.CreateEnquiryRequest;
import com.zaza.housing.dto.MarkConversationReadRequest;
import com.zaza.housing.dto.OpenConversationRequest;
import com.zaza.housing.dto.RedactConversationMessageRequest;
import com.zaza.housing.dto.SendMessageRequest;
import com.zaza.housing.enums.ConversationChannel;
import com.zaza.housing.enums.ConversationKind;
import com.zaza.housing.enums.ConversationRole;
import com.zaza.housing.enums.LeaseStatus;
import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.enums.WorkKind;
import com.zaza.housing.enums.WorkParty;
import com.zaza.housing.enums.WorkStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.ConversationRepository;
import com.zaza.housing.repository.ViewingRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationService {
  private final ConversationRepository conversations;
  private final ConversationAccessService access;
  private final ConversationActivityService activity;
  private final ListingService listings;
  private final LeaseAccessService leases;
  private final WorkAccessService work;
  private final OrganizationService organizations;
  private final ViewingRepository viewings;
  private final Clock clock;
  private final int messageLimit;
  private final int enquiryLimit;

  public ConversationService(
      ConversationRepository conversations,
      ConversationAccessService access,
      ConversationActivityService activity,
      ListingService listings,
      LeaseAccessService leases,
      WorkAccessService work,
      OrganizationService organizations,
      ViewingRepository viewings,
      Clock clock,
      @Value("${zaza.communication.messages-per-minute:30}") int messageLimit,
      @Value("${zaza.communication.enquiries-per-hour:20}") int enquiryLimit) {
    this.conversations = conversations;
    this.access = access;
    this.activity = activity;
    this.listings = listings;
    this.leases = leases;
    this.work = work;
    this.organizations = organizations;
    this.viewings = viewings;
    this.clock = clock;
    this.messageLimit = messageLimit;
    this.enquiryLimit = enquiryLimit;
    if (messageLimit < 1 || enquiryLimit < 1)
      throw new IllegalArgumentException("Communication rate limits must be positive.");
  }

  public static String text(String value) {
    String result = value.strip();
    if (result.isBlank()
        || result
            .codePoints()
            .anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t'))
      throw WorkAccessService.invalid("Use nonblank plain text without control characters.");
    return result;
  }

  public ConversationResponse get(AuthenticatedUser actor, UUID id) {
    return conversations
        .response(actor.id(), id)
        .orElseThrow(() -> WorkAccessService.missing("Conversation not found."));
  }

  private void organization(AuthenticatedUser actor, UUID org) {
    if (org != null && organizations.get(actor, org).role() == MembershipRole.MEMBER)
      throw WorkAccessService.forbidden("Organization management permission is required.");
  }

  public List<ConversationResponse> list(
      AuthenticatedUser actor,
      UUID org,
      ConversationKind kind,
      String status,
      boolean unread,
      int limit,
      int offset) {
    Pagination.validate(limit, offset);
    organization(actor, org);
    if (status != null && !Set.of("OPEN", "CLOSED").contains(status))
      throw WorkAccessService.invalid("Unknown conversation status.");
    return conversations.list(actor.id(), org, kind, status, unread, limit, offset);
  }

  public ConversationInboxSummaryResponse summary(AuthenticatedUser actor, UUID org) {
    organization(actor, org);
    return conversations.summary(actor.id(), org);
  }

  public List<ConversationMessageResponse> messages(
      AuthenticatedUser actor, UUID id, long after, int limit) {
    Pagination.validate(limit, 0);
    if (after < 0) throw WorkAccessService.invalid("Message sequence cannot be negative.");
    access.get(actor, id, false);
    return conversations.messages(actor.id(), id, after, limit);
  }

  public List<ConversationEventResponse> history(
      AuthenticatedUser actor, UUID id, int limit, int offset) {
    Pagination.validate(limit, offset);
    access.get(actor, id, false);
    return conversations.history(actor.id(), id, limit, offset);
  }

  @Transactional
  public ConversationResponse enquiry(
      AuthenticatedUser actor, UUID listingId, CreateEnquiryRequest r) {
    var replay = activity.replay(actor, listingId, r.requestId(), "ENQUIRY_OPENED", r);
    if (replay.isPresent()) {
      access.get(actor, replay.get(), true);
      return get(actor, replay.get());
    }
    conversations.lockEnquiryCustomer(actor.id());
    var source = listings.applicationSource(listingId);
    replay = activity.replay(actor, listingId, r.requestId(), "ENQUIRY_OPENED", r);
    if (replay.isPresent()) return get(actor, replay.get());
    if (source.version() != r.listingVersion())
      throw WorkAccessService.conflict(
          "Review the latest published listing before contacting its manager.");
    if (conversations.manages(actor.id(), source.propertyId()).isPresent())
      throw WorkAccessService.forbidden("Use the management inbox for properties you manage.");
    if (conversations.enquiry(listingId, actor.id()).isPresent())
      throw WorkAccessService.conflict(
          "An enquiry already exists for this listing. Continue it from your inbox.");
    if (conversations.recentEnquiries(actor.id(), clock.instant().minus(Duration.ofHours(1)))
        >= enquiryLimit)
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS, "The hourly enquiry limit was reached. Try later.");
    UUID id = UUID.randomUUID();
    conversations.create(
        id,
        ConversationKind.ENQUIRY,
        ConversationChannel.SHARED,
        listingId,
        null,
        null,
        source.propertyId(),
        source.unitId(),
        actor.id(),
        null,
        null,
        source.title(),
        clock.instant());
    var a = access.get(actor, id, false);
    UUID message =
        conversations.message(
            a.conversation(),
            actor.id(),
            ConversationRole.CUSTOMER,
            text(r.message()),
            clock.instant());
    activity.operation(actor, listingId, r.requestId(), "ENQUIRY_OPENED", r, id);
    activity.finish(actor, a, r.requestId(), "ENQUIRY_OPENED", r, message, true);
    return get(actor, id);
  }

  @Transactional
  public ConversationResponse lease(
      AuthenticatedUser actor, UUID org, UUID property, UUID lease, OpenConversationRequest r) {
    var source =
        org == null
            ? leases.tenant(actor, lease, true)
            : leases.manager(actor, org, property, lease, true);
    if (r.channel() != ConversationChannel.SHARED)
      throw WorkAccessService.invalid("Lease conversations use the shared channel.");
    LeaseAccessService.state(
        source,
        LeaseStatus.SIGNED,
        LeaseStatus.ACTIVE,
        LeaseStatus.COMPLETED,
        LeaseStatus.TERMINATED);
    var replay = activity.replay(actor, lease, r.requestId(), "LEASE_OPENED", r);
    if (replay.isPresent()) return get(actor, replay.get());
    var old = conversations.lease(lease);
    if (old.isPresent()) {
      activity.operation(actor, lease, r.requestId(), "LEASE_OPENED", r, old.get());
      return get(actor, old.get());
    }
    UUID id = UUID.randomUUID();
    conversations.create(
        id,
        ConversationKind.LEASE,
        ConversationChannel.SHARED,
        null,
        lease,
        null,
        source.propertyId(),
        source.unitId(),
        source.tenantId(),
        null,
        null,
        "Lease discussion",
        clock.instant());
    activity.operation(actor, lease, r.requestId(), "LEASE_OPENED", r, id);
    var a = access.get(actor, id, false);
    activity.finish(actor, a, r.requestId(), "LEASE_OPENED", r, id, true);
    return get(actor, id);
  }

  @Transactional
  public ConversationResponse work(
      AuthenticatedUser actor,
      WorkParty party,
      UUID org,
      UUID property,
      UUID workId,
      OpenConversationRequest r) {
    var a = work.get(actor, party, org, property, workId, true);
    var source = a.work();
    if (party == WorkParty.PROVIDER && source.status() == WorkStatus.ASSIGNED)
      throw WorkAccessService.conflict("Accept the job before opening customer conversations.");
    if (source.kind() == WorkKind.SERVICE_BOOKING && r.channel() != ConversationChannel.SHARED)
      throw WorkAccessService.invalid("Personal service bookings use the shared channel.");
    if(r.channel()==ConversationChannel.PROCUREMENT && party==WorkParty.COORDINATOR) work.manager(actor,a);
    if (r.channel() == ConversationChannel.PROCUREMENT && party == WorkParty.CUSTOMER)
      throw WorkAccessService.forbidden("Procurement discussions exclude maintenance occupants.");
    var replay = activity.replay(actor, workId, r.requestId(), "WORK_OPENED", r);
    if (replay.isPresent()) return get(actor, replay.get());
    var old = conversations.work(workId, source.assignmentId(), r.channel());
    if (old.isPresent()) {
      activity.operation(actor, workId, r.requestId(), "WORK_OPENED", r, old.get());
      return get(actor, old.get());
    }
    UUID id = UUID.randomUUID();
    conversations.create(
        id,
        ConversationKind.WORK,
        r.channel(),
        null,
        null,
        workId,
        source.propertyId(),
        source.unitId(),
        source.tenantId(),
        source.providerId(),
        source.assignmentId(),
        source.title(),
        clock.instant());
    activity.operation(actor, workId, r.requestId(), "WORK_OPENED", r, id);
    var current = access.get(actor, id, false);
    activity.finish(actor, current, r.requestId(), "WORK_OPENED", r, id, true);
    return get(actor, id);
  }

  @Transactional
  public ConversationResponse send(AuthenticatedUser actor, UUID id, SendMessageRequest r) {
    var a = access.get(actor, id, true);
    if (activity.replay(actor, id, r.requestId(), "MESSAGE_SENT", r).isPresent())
      return get(actor, id);
    access.open(a.conversation());
    if (conversations.recentMessages(id, actor.id(), clock.instant().minus(Duration.ofMinutes(1)))
        >= messageLimit)
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "The conversation message rate limit was reached. Try later.");
    UUID message =
        conversations.message(
            a.conversation(), actor.id(), a.role(), text(r.body()), clock.instant());
    activity.finish(actor, a, r.requestId(), "MESSAGE_SENT", r, message, true);
    return get(actor, id);
  }

  @Transactional
  public ConversationResponse status(
      AuthenticatedUser actor, UUID id, ConversationCommandRequest r, boolean close) {
    var a = access.get(actor, id, true);
    access.managerOrCustomer(a);
    String action = close ? "CLOSED" : "REOPENED";
    if (activity.replay(actor, id, r.requestId(), action, r).isPresent()) return get(actor, id);
    access.version(a.conversation(), r.version());
    if (a.conversation().status().equals(close ? "CLOSED" : "OPEN"))
      throw WorkAccessService.conflict("The conversation already has this status.");
    if (close && viewings.active(id))
      throw WorkAccessService.conflict(
          "Cancel or complete the active viewing before closing this enquiry.");
    conversations.status(id, close ? "CLOSED" : "OPEN");
    activity.finish(actor, a, r.requestId(), action, r, id, true);
    return get(actor, id);
  }

  @Transactional
  public ConversationResponse read(
      AuthenticatedUser actor, UUID id, MarkConversationReadRequest r) {
    var a = access.get(actor, id, true);
    if (activity.replay(actor, id, r.requestId(), "READ", r).isPresent()) return get(actor, id);
    if (r.throughSequence() > a.conversation().lastSequence())
      throw WorkAccessService.invalid("Read cursor cannot pass the last message.");
    conversations.read(id, actor.id(), r.throughSequence());
    activity.personal(actor, a, r.requestId(), "READ", r);
    return get(actor, id);
  }

  @Transactional
  public ConversationResponse preferences(
      AuthenticatedUser actor, UUID id, ConversationPreferenceRequest r) {
    var a = access.get(actor, id, true);
    if (activity.replay(actor, id, r.requestId(), "PREFERENCES", r).isPresent())
      return get(actor, id);
    conversations.mute(id, actor.id(), r.muted());
    activity.personal(actor, a, r.requestId(), "PREFERENCES", r);
    return get(actor, id);
  }

  @Transactional
  public ConversationResponse redact(
      AuthenticatedUser actor, UUID id, UUID message, RedactConversationMessageRequest r) {
    var a = access.get(actor, id, true);
    var body = Map.of("messageId", message, "request", r);
    if (activity.replay(actor, id, r.requestId(), "MESSAGE_REDACTED", body).isPresent())
      return get(actor, id);
    var m =
        conversations
            .message(id, message)
            .orElseThrow(() -> WorkAccessService.missing("Message not found."));
    if (!actor.id().equals(m.get("author_id")))
      throw WorkAccessService.forbidden("Only the author can withdraw a message directly.");
    if (clock
        .instant()
        .isAfter(
            ((java.sql.Timestamp) m.get("created_at")).toInstant().plus(Duration.ofMinutes(15))))
      throw WorkAccessService.conflict(
          "Direct withdrawal is available for 15 minutes. Report sensitive content for moderation"
              + " after that.");
    if (Boolean.TRUE.equals(m.get("redacted")))
      throw WorkAccessService.conflict("The message was already withdrawn.");
    conversations.redact(message, actor.id(), text(r.reason()), clock.instant());
    activity.finish(actor, a, r.requestId(), "MESSAGE_REDACTED", body, message, true);
    return get(actor, id);
  }
}
