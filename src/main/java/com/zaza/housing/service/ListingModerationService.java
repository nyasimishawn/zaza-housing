package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.*;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ListingModerationService {
  private final AdministrationRepository repository;
  private final AdministrationAccessService access;
  private final AdministrationActivityService activity;
  private final ListingRepository listings;
  private final Clock clock;
  private final ObjectMapper json;

  public ListingModerationService(
      AdministrationRepository repository,
      AdministrationAccessService access,
      AdministrationActivityService activity,
      ListingRepository listings,
      Clock clock,
      ObjectMapper json) {
    this.repository = repository;
    this.access = access;
    this.activity = activity;
    this.listings = listings;
    this.clock = clock;
    this.json = json;
  }

  public List<Map<String, Object>> sources(
      AuthenticatedUser a, String token, String q, int l, int f) {
    access.admin(a, token, false);
    Pagination.validate(l, f);
    if (q != null && q.length() > 150) throw AgencyAccessService.bad("Search is too long.");
    return listings.moderationSources(q == null ? "" : q.strip(), l, f);
  }

  public List<Map<String, Object>> list(
      AuthenticatedUser a, String token, String status, int l, int f) {
    access.admin(a, token, false);
    Pagination.validate(l, f);
    return repository.reviews(status, l, f);
  }

  public Map<String, Object> get(AuthenticatedUser a, String token, UUID id) {
    access.admin(a, token, false);
    var review = repository.review(id, false);
    if (review == null) throw CaretakerAccessService.missing();
    var result = new LinkedHashMap<>(review);
    var source = repository.listing(id, false);
    result.put("currentListingVersion", source.get("version"));
    result.put(
        "sourceChanged",
        !Objects.equals(
            ((Number) source.get("version")).longValue(),
            ((Number) review.get("listingVersion")).longValue()));
    return result;
  }

  @Transactional
  public Map<String, Object> request(AuthenticatedUser a, String token, ListingReviewRequest r) {
    access.admin(a, token, true);
    access.password(a, r.password());
    var source = access.listing(a, r.listingId(), true);
    if (activity.replay(a, r.requestId(), "LISTING_REVIEW_REQUESTED", r.listingId(), r) != null)
      return get(a, token, r.listingId());
    AdministrationAccessService.version(source.get("version"), r.listingVersion());
    var snapshot =
        listings.publicListing(r.listingId()).orElseThrow(CaretakerAccessService::missing);
    repository.requestReview(
        r.listingId(), r.listingVersion(), snapshot, r.reason().trim(), clock.instant());
    activity.record(
        a,
        r.requestId(),
        "LISTING_REVIEW_REQUESTED",
        "LISTING",
        r.listingId(),
        r,
        r.reason().trim(),
        false);
    return get(a, token, r.listingId());
  }

  @Transactional
  public Map<String, Object> command(
      AuthenticatedUser a, String token, UUID id, String command, AdministrationCommandRequest r) {
    access.admin(a, token, true);
    access.password(a, r.password());
    var source = access.listing(a, id, true);
    var review = repository.review(id, true);
    if (review == null) throw CaretakerAccessService.missing();
    String action = "LISTING_REVIEW_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(a, r.requestId(), action, id, r) != null) return get(a, token, id);
    AdministrationAccessService.version(review.get("version"), r.version());
    UUID assigned = (UUID) review.get("assignedTo");
    String status = (String) review.get("status");
    Object snapshot = review.get("publicSnapshot");
    long version = ((Number) review.get("listingVersion")).longValue();
    if (command.equals("claim")) {
      if (assigned != null && !assigned.equals(a.id()))
        throw AgencyAccessService.conflict("Another operator owns this review.");
      assigned = a.id();
    } else {
      if (!a.id().equals(assigned))
        throw WorkAccessService.forbidden("Claim this review before changing it.");
      switch (command) {
        case "release" -> assigned = null;
        case "refresh" -> {
          if (!"PUBLISHED".equals(source.get("status")) || source.get("public_snapshot") == null)
            throw AgencyAccessService.conflict("The listing has no published snapshot to review.");
          snapshot = source.get("public_snapshot");
          version = ((Number) source.get("version")).longValue();
        }
        case "approve", "reject", "suspend" -> {
          if (!"PUBLISHED".equals(source.get("status")) || source.get("public_snapshot") == null)
            throw AgencyAccessService.conflict(
                "Review a currently published snapshot before deciding.");
          if (r.sourceVersion() == null)
            throw AgencyAccessService.bad("Confirm the reviewed listing version.");
          AdministrationAccessService.version(source.get("version"), r.sourceVersion());
          AdministrationAccessService.version(review.get("listingVersion"), r.sourceVersion());
          status =
              command.equals("approve")
                  ? "APPROVED"
                  : command.equals("reject") ? "REJECTED" : "SUSPENDED";
          assigned = null;
        }
        default -> throw AgencyAccessService.bad("Unknown listing review action.");
      }
    }
    // PGobject snapshots are already JSON, not a JSON-encoded string.
    if (snapshot != null && !(snapshot instanceof Map))
      snapshot = json.readValue(snapshot.toString(), Object.class);
    repository.review(id, status, assigned, version, r.reason().trim(), snapshot, clock.instant());
    activity.record(a, r.requestId(), action, "LISTING", id, r, r.reason().trim(), false);
    return get(a, token, id);
  }
}
