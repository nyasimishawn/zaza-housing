package com.zaza.housing.service;

import com.zaza.housing.dto.AgencyClientLinkRequest;
import com.zaza.housing.dto.AgencyClientRequest;
import com.zaza.housing.dto.AgencyCommandRequest;
import com.zaza.housing.enums.ConversationKind;
import com.zaza.housing.model.AgencyClient;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Conversation;
import com.zaza.housing.repository.AgencyRepository;
import com.zaza.housing.repository.ConversationRepository;
import java.time.Clock;
import java.util.Arrays;
import java.util.Currency;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgencyClientService {
  private final AgencyAccessService access;
  private final AgencyRepository repository;
  private final AgencyActivityService activity;
  private final ConversationRepository conversations;
  private final Clock clock;

  public AgencyClientService(
      AgencyAccessService access,
      AgencyRepository repository,
      AgencyActivityService activity,
      ConversationRepository conversations,
      Clock clock) {
    this.access = access;
    this.repository = repository;
    this.activity = activity;
    this.conversations = conversations;
    this.clock = clock;
  }

  public AgencyClient get(AuthenticatedUser actor, UUID org, UUID id) {
    access.get(actor, org, false, false);
    return repository.client(org, actor.id(), id, false).orElseThrow(AgencyAccessService::missing);
  }

  public Conversation enquiry(AuthenticatedUser actor, UUID org, UUID id) {
    var c = conversations.find(id).orElseThrow(AgencyAccessService::missing);
    if (c.kind() != ConversationKind.ENQUIRY)
      throw AgencyAccessService.bad("Choose a listing enquiry.");
    access.property(actor, org, c.propertyId(), null, true);
    return c;
  }

  @Transactional
  public AgencyClient save(AuthenticatedUser actor, UUID org, UUID id, AgencyClientRequest r) {
    access.get(actor, org, true, true);
    UUID replay =
        activity.replay(
            actor,
            org,
            r.requestId(),
            id == null ? "AGENCY_CLIENT_CREATED" : "AGENCY_CLIENT_UPDATED",
            id,
            r);
    if (replay != null) return get(actor, org, replay);
    var d = r.details();
    try {
      Currency.getInstance(d.currency());
      if (!Arrays.asList(Locale.getISOCountries()).contains(d.countryCode()))
        throw new IllegalArgumentException();
    } catch (IllegalArgumentException e) {
      throw AgencyAccessService.bad("Use valid uppercase country and currency codes.");
    }
    if (d.minBudget() != null
        && d.maxBudget() != null
        && d.minBudget().compareTo(d.maxBudget()) > 0)
      throw AgencyAccessService.bad("Maximum budget must cover the minimum budget.");
    String action;
    if (id == null) {
      UUID customer =
          r.enquiryId() == null ? null : enquiry(actor, org, r.enquiryId()).customerId();
      id = repository.createClient(org, actor.id(), customer, d, clock.instant());
      action = "AGENCY_CLIENT_CREATED";
    } else {
      var c =
          repository.client(org, actor.id(), id, true).orElseThrow(AgencyAccessService::missing);
      AgencyAccessService.version(c.version(), r.version());
      if (r.enquiryId() != null)
        throw AgencyAccessService.bad("Use the registered customer link action.");
      repository.clientDetails(id, d, clock.instant());
      action = "AGENCY_CLIENT_UPDATED";
    }
    activity.record(
        actor,
        org,
        r.requestId(),
        action,
        "CLIENT",
        id,
        r,
        "Client contact and preferences saved with consent.");
    return get(actor, org, id);
  }

  @Transactional
  public AgencyClient link(AuthenticatedUser actor, UUID org, UUID id, AgencyClientLinkRequest r) {
    access.get(actor, org, true, true);
    var c = repository.client(org, actor.id(), id, true).orElseThrow(AgencyAccessService::missing);
    var enquiry = enquiry(actor, org, r.enquiryId());
    if (activity.replay(actor, org, r.requestId(), "AGENCY_CLIENT_LINKED", id, r) != null)
      return get(actor, org, id);
    AgencyAccessService.version(c.version(), r.version());
    if (c.customerUserId() != null && !c.customerUserId().equals(enquiry.customerId()))
      throw AgencyAccessService.conflict(
          "This client already has a different registered customer.");
    repository.bindCustomer(id, enquiry.customerId(), clock.instant());
    activity.record(
        actor, org, r.requestId(), "AGENCY_CLIENT_LINKED", "CLIENT", id, r, r.message());
    return get(actor, org, id);
  }

  @Transactional
  public AgencyClient status(
      AuthenticatedUser actor, UUID org, UUID id, String status, AgencyCommandRequest r) {
    access.get(actor, org, true, true);
    var c = repository.client(org, actor.id(), id, true).orElseThrow(AgencyAccessService::missing);
    String action = "AGENCY_CLIENT_" + status;
    if (activity.replay(actor, org, r.requestId(), action, id, r) != null)
      return get(actor, org, id);
    AgencyAccessService.version(c.version(), r.version());
    if (!Set.of("ACTIVE", "ARCHIVED").contains(status))
      throw AgencyAccessService.bad("Choose ACTIVE or ARCHIVED.");
    repository.clientStatus(id, status, clock.instant());
    activity.record(actor, org, r.requestId(), action, "CLIENT", id, r, r.message());
    return get(actor, org, id);
  }
}
