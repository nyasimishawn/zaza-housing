package com.zaza.housing.service;

import com.zaza.housing.dto.AgencyLeadCreateRequest;
import com.zaza.housing.dto.AgencyLeadMutationRequest;
import com.zaza.housing.model.AgencyClient;
import com.zaza.housing.model.AgencyLead;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.AgencyRepository;
import com.zaza.housing.repository.ListingRepository;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AgencyLeadService {
  private final AgencyAccessService access;
  private final AgencyRepository repository;
  private final AgencyActivityService activity;
  private final AgencyClientService clients;
  private final ListingRepository listings;
  private final Clock clock;

  public AgencyLeadService(
      AgencyAccessService access,
      AgencyRepository repository,
      AgencyActivityService activity,
      AgencyClientService clients,
      ListingRepository listings,
      Clock clock) {
    this.access = access;
    this.repository = repository;
    this.activity = activity;
    this.clients = clients;
    this.listings = listings;
    this.clock = clock;
  }

  public AgencyLead get(AuthenticatedUser actor, UUID org, UUID id) {
    access.get(actor, org, false, false);
    return access.lead(actor, org, id, false);
  }

  private void links(
      AuthenticatedUser actor,
      UUID org,
      AgencyClient c,
      UUID property,
      UUID listing,
      UUID enquiry,
      UUID agent) {
    if (!"ACTIVE".equals(c.status()))
      throw AgencyAccessService.conflict("Restore the client before progressing this lead.");
    access.agent(org, agent);
    access.property(actor, org, property, agent, true);
    if (listing != null) {
      if (property == null || listings.find(property, listing).isEmpty())
        throw AgencyAccessService.bad("Choose a listing on this property.");
    }
    if (enquiry != null) {
      var e = clients.enquiry(actor, org, enquiry);
      if (!Objects.equals(e.propertyId(), property)
          || !Objects.equals(e.listingId(), listing)
          || !Objects.equals(e.customerId(), c.customerUserId()))
        throw AgencyAccessService.bad(
            "The enquiry, listing and registered customer must match this client and property.");
    }
  }

  @Transactional
  public AgencyLead create(AuthenticatedUser actor, UUID org, AgencyLeadCreateRequest r) {
    access.get(actor, org, true, true);
    UUID replay = activity.replay(actor, org, r.requestId(), "AGENCY_LEAD_CREATED", null, r);
    if (replay != null) return get(actor, org, replay);
    var c = clients.get(actor, org, r.clientId());
    links(actor, org, c, r.propertyId(), r.listingId(), r.enquiryId(), r.agentId());
    UUID id = repository.createLead(org, actor.id(), r, clock.instant());
    activity.record(
        actor, org, r.requestId(), "AGENCY_LEAD_CREATED", "LEAD", id, r, "Lead created.");
    return get(actor, org, id);
  }

  @Transactional
  public AgencyLead mutate(
      AuthenticatedUser actor, UUID org, UUID id, String command, AgencyLeadMutationRequest r) {
    var o =
        access.get(actor, org, true, !Set.of("stage", "note", "call", "email").contains(command));
    var l = access.lead(actor, org, id, true);
    String action = "AGENCY_LEAD_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(actor, org, r.requestId(), action, id, r) != null)
      return get(actor, org, id);
    AgencyAccessService.version(l.version(), r.version());
    var c = clients.get(actor, org, l.clientId());
    switch (command) {
      case "assign" -> {
        access.agent(org, r.agentId());
        access.property(actor, org, l.propertyId(), r.agentId(), true);
        repository.leadAssignment(id, r.agentId(), clock.instant());
      }
      case "link" -> {
        if (!Set.of("NEW", "QUALIFIED", "LOST").contains(l.stage()))
          throw AgencyAccessService.conflict("Only an early-stage lead can change property links.");
        links(actor, org, c, r.propertyId(), r.listingId(), r.enquiryId(), l.assignedTo());
        repository.leadLinks(id, r.propertyId(), r.listingId(), r.enquiryId(), clock.instant());
      }
      case "stage" -> {
        if (r.stage() == null) throw AgencyAccessService.bad("Choose a lead stage.");
        var transitions =
            Map.of(
                "NEW",
                Set.of("QUALIFIED", "LOST"),
                "QUALIFIED",
                Set.of("VIEWING", "APPLICATION", "LOST"),
                "VIEWING",
                Set.of("QUALIFIED", "APPLICATION", "LOST"),
                "APPLICATION",
                Set.of("QUALIFIED", "WON", "LOST"),
                "LOST",
                Set.of("NEW"),
                "WON",
                Set.<String>of());
        if (!transitions.get(l.stage()).contains(r.stage()))
          throw AgencyAccessService.conflict("This stage transition is unavailable.");
        if (l.stage().equals("LOST")) access.manager(o);
        links(actor, org, c, l.propertyId(), l.listingId(), l.enquiryId(), l.assignedTo());
        var choices = repository.workflow(l, c.customerUserId());
        UUID viewing = r.viewingId(), application = r.applicationId(), lease = r.leaseId();
        if ("VIEWING".equals(r.stage())
            && choices.stream()
                .noneMatch(
                    x ->
                        x.id().equals(viewing)
                            && x.kind().equals("VIEWING")
                            && Set.of("CONFIRMED", "COMPLETED").contains(x.status())))
          throw AgencyAccessService.bad("Link a confirmed or completed viewing from this enquiry.");
        if ("APPLICATION".equals(r.stage())
            && choices.stream()
                .noneMatch(
                    x ->
                        x.id().equals(r.applicationId())
                            && x.kind().equals("APPLICATION")
                            && Set.of("SUBMITTED", "UNDER_REVIEW", "APPROVED", "LEASED")
                                .contains(x.status())))
          throw AgencyAccessService.bad(
              "Link a current submitted application from this customer and property.");
        if ("WON".equals(r.stage())) {
          if (choices.stream()
              .noneMatch(
                  x ->
                      x.id().equals(lease)
                          && x.kind().equals("LEASE")
                          && Set.of("SIGNED", "ACTIVE", "COMPLETED").contains(x.status())))
            throw AgencyAccessService.bad(
                "A signed lease from this customer and property is required.");
          application = repository.leaseApplication(lease);
          if (!Objects.equals(application, l.applicationId()))
            throw AgencyAccessService.bad(
                "The signed lease must originate from this lead's application.");
        }
        repository.leadStage(
            id,
            r.stage(),
            "VIEWING".equals(r.stage()) ? viewing : null,
            "APPLICATION".equals(r.stage()) || "WON".equals(r.stage()) ? application : null,
            "WON".equals(r.stage()) ? lease : null,
            clock.instant());
      }
      case "note", "call", "email" -> {
        /* Append-only interaction; no external message is sent. */
      }
      default -> throw AgencyAccessService.bad("Unknown lead action.");
    }
    activity.record(actor, org, r.requestId(), action, "LEAD", id, r, r.message());
    return get(actor, org, id);
  }
}
