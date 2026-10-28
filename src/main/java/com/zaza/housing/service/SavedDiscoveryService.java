package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.*;
import com.zaza.housing.util.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class SavedDiscoveryService {
  private final SavedDiscoveryRepository repository;
  private final DiscoveryRepository discovery;
  private final DiscoveryReadService reads;
  private final DiscoveryQueryService queries;
  private final ObjectMapper json;
  private final Clock clock;
  private final AuditService audit;

  public SavedDiscoveryService(
      SavedDiscoveryRepository repository,
      DiscoveryRepository discovery,
      DiscoveryReadService reads,
      DiscoveryQueryService queries,
      ObjectMapper json,
      Clock clock,
      AuditService audit) {
    this.repository = repository;
    this.discovery = discovery;
    this.reads = reads;
    this.queries = queries;
    this.json = json;
    this.clock = clock;
    this.audit = audit;
  }

  private void actor(AuthenticatedUser a, String token, boolean lock) {
    if (!repository.actor(a.id(), token, clock.instant(), lock))
      throw new ApiException(HttpStatus.UNAUTHORIZED, "Sign in again.");
  }

  private ApiException missing() {
    return new ApiException(HttpStatus.NOT_FOUND, "Saved record not found.");
  }

  private ApiException conflict(String text) {
    return new ApiException(HttpStatus.CONFLICT, text);
  }

  private Map<String, Object> get(UUID user, String kind, UUID id, boolean lock) {
    var row = repository.get(user, kind, id, lock);
    if (row == null) throw missing();
    return row;
  }

  private Instant time(Map<String, Object> r, String key) {
    return r.get(key) == null ? null : ((Timestamp) r.get(key)).toInstant();
  }

  private UUID id(Map<String, Object> r, String key) {
    return (UUID) r.get(key);
  }

  private long version(Map<String, Object> r) {
    return ((Number) r.get("version")).longValue();
  }

  private void version(Map<String, Object> r, Long expected) {
    if (expected == null || version(r) != expected)
      throw conflict("This saved record changed. Reload before editing.");
  }

  private String name(String value) {
    if (value == null || value.isBlank() || value.strip().length() > 100)
      throw DiscoveryQueryService.bad("Name this saved record using 1–100 characters.");
    return value.strip();
  }

  private String frequency(String value) {
    if (value == null) return "OFF";
    if (!Set.of("OFF", "HOURLY", "DAILY").contains(value))
      throw DiscoveryQueryService.bad("Choose paused, hourly or daily alerts.");
    return value;
  }

  public static Instant next(String frequency, Instant now) {
    return now.plusSeconds(frequency.equals("HOURLY") ? 3600 : 86400);
  }

  private String fingerprint(Object value) {
    return RequestFingerprints.of(json, value);
  }

  private UUID replay(AuthenticatedUser a, DiscoveryCommandRequest r, String action, UUID target) {
    var old = repository.command(r.requestId());
    if (old == null) return null;
    if (!a.id().equals(old.get("user_id"))
        || !action.equals(old.get("action"))
        || (target != null && !target.equals(old.get("target_id")))
        || !fingerprint(r).equals(old.get("fingerprint")))
      throw conflict("This request ID was used for different content. Reload before continuing.");
    return id(old, "target_id");
  }

  private void record(AuthenticatedUser a, DiscoveryCommandRequest r, String action, UUID id) {
    repository.command(r.requestId(), a.id(), action, id, fingerprint(r), clock.instant());
    audit.record(a.id(), null, action, id);
  }

  public DiscoverySavedItemResponse item(Map<String, Object> r) {
    var current = reads.item((String) r.get("kind"), id(r, "target_id"));
    return new DiscoverySavedItemResponse(
        id(r, "id"),
        (String) r.get("kind"),
        id(r, "target_id"),
        (String) r.get("folder"),
        (String) r.get("note"),
        version(r),
        time(r, "created_at"),
        time(r, "updated_at"),
        current != null,
        current);
  }

  public DiscoverySavedSearchResponse search(Map<String, Object> r) {
    return new DiscoverySavedSearchResponse(
        id(r, "id"),
        (String) r.get("name"),
        json.readValue(r.get("query").toString(), DiscoveryQuery.class),
        (String) r.get("frequency"),
        version(r),
        time(r, "created_at"),
        time(r, "updated_at"),
        time(r, "last_scan_at"),
        time(r, "next_scan_at"),
        (String) r.get("last_error"));
  }

  private List<UUID> ids(Map<String, Object> r) {
    return Arrays.stream(json.readValue(r.get("listing_ids").toString(), String[].class))
        .map(UUID::fromString)
        .toList();
  }

  public DiscoveryComparisonResponse comparison(Map<String, Object> r) {
    var ids = ids(r);
    var rows = reads.compare(ids);
    return new DiscoveryComparisonResponse(
        id(r, "id"),
        (String) r.get("name"),
        ids,
        version(r),
        time(r, "created_at"),
        time(r, "updated_at"),
        rows,
        reads.currency(rows));
  }

  private DiscoveryAlertResponse alert(UUID user, Map<String, Object> r) {
    var s = get(user, "searches", id(r, "search_id"), false);
    var q = json.readValue(s.get("query").toString(), DiscoveryQuery.class);
    var current =
        id(r, "generation").equals(id(s, "generation"))
            ? reads.matching(q, id(r, "target_id"))
            : null;
    return new DiscoveryAlertResponse(
        id(r, "id"),
        id(s, "id"),
        (String) s.get("name"),
        q.kind(),
        id(r, "target_id"),
        time(r, "created_at"),
        time(r, "read_at"),
        current != null,
        current);
  }

  public Object read(AuthenticatedUser a, String token, String kind, UUID id) {
    actor(a, token, false);
    var r = get(a.id(), kind, id, false);
    return switch (kind) {
      case "items" -> item(r);
      case "searches" -> search(r);
      case "comparisons" -> comparison(r);
      case "alerts" -> alert(a.id(), r);
      default -> throw missing();
    };
  }

  public List<?> list(
      AuthenticatedUser a, String token, String kind, int limit, int offset, String folder) {
    actor(a, token, false);
    Pagination.validate(limit, offset);
    if (folder != null && folder.length() > 80)
      throw DiscoveryQueryService.bad("Use a folder of at most 80 characters.");
    return repository.list(a.id(), kind, limit, offset, folder).stream()
        .map(
            r ->
                switch (kind) {
                  case "items" -> item(r);
                  case "searches" -> search(r);
                  case "comparisons" -> comparison(r);
                  case "alerts" -> alert(a.id(), r);
                  default -> throw missing();
                })
        .toList();
  }

  @Transactional
  public Object create(AuthenticatedUser a, String token, String kind, DiscoveryCommandRequest r) {
    actor(a, token, true);
    String action = "DISCOVERY_" + kind.toUpperCase(Locale.ROOT) + "_CREATED";
    UUID replay = replay(a, r, action, null);
    if (replay != null) return read(a, token, kind, replay);
    UUID id = UUID.randomUUID();
    Instant now = clock.instant();
    long maximum = kind.equals("items") ? 500 : kind.equals("searches") ? 25 : 20;
    if (kind.equals("items")) {
      if (r.targetId() == null
          || r.kind() == null
          || !Set.of("PROPERTY", "PROVIDER").contains(r.kind()))
        throw DiscoveryQueryService.bad("Choose a public property or provider.");
      var current = reads.item(r.kind(), r.targetId());
      if (current == null) throw missing();
      var existing = repository.existingItem(a.id(), r.kind(), r.targetId());
      if (existing != null) {
        record(a, r, action, existing);
        return read(a, token, kind, existing);
      }
    }
    if (repository.count(a.id(), kind) >= maximum)
      throw conflict("Saved record limit reached. Remove an older record first.");
    switch (kind) {
      case "items" -> repository.item(id, a.id(), r, now);
      case "searches" -> {
        var q = queries.normalize(r.query(), true);
        String frequency = frequency(r.frequency());
        UUID generation = UUID.randomUUID();
        repository.search(
            id, a.id(), name(r.name()), q, frequency, generation, next(frequency, now), now);
        discovery.baseline(id, generation, q);
      }
      case "comparisons" -> {
        var rows = reads.compare(r.listingIds());
        if (rows.stream().anyMatch(v -> !v.available()))
          throw conflict("Choose currently public rentals before saving a comparison.");
        repository.comparison(id, a.id(), name(r.name()), r.listingIds(), now);
      }
      default -> throw missing();
    }
    record(a, r, action, id);
    return read(a, token, kind, id);
  }

  @Transactional
  public Object update(
      AuthenticatedUser a, String token, String kind, UUID id, DiscoveryCommandRequest r) {
    actor(a, token, true);
    String action = "DISCOVERY_" + kind.toUpperCase(Locale.ROOT) + "_UPDATED";
    if (replay(a, r, action, id) != null) return read(a, token, kind, id);
    var old = get(a.id(), kind, id, true);
    version(old, r.version());
    Instant now = clock.instant();
    switch (kind) {
      case "items" -> {
        if (r.kind() != null && !r.kind().equals(old.get("kind"))
            || r.targetId() != null && !r.targetId().equals(old.get("target_id")))
          throw conflict("The saved target cannot be changed.");
        repository.editItem(id, r.folder(), r.note(), now);
      }
      case "searches" -> {
        var q = queries.normalize(r.query(), true);
        String f = frequency(r.frequency());
        boolean reset =
            !json.writeValueAsString(q)
                    .equals(
                        json.writeValueAsString(
                            json.readValue(old.get("query").toString(), DiscoveryQuery.class)))
                || "OFF".equals(old.get("frequency")) && !f.equals("OFF");
        UUID generation = reset ? UUID.randomUUID() : id(old, "generation");
        repository.editSearch(id, name(r.name()), q, f, generation, next(f, now), now, reset);
        if (reset) discovery.baseline(id, generation, q);
      }
      case "comparisons" -> {
        var rows = reads.compare(r.listingIds());
        if (rows.stream().anyMatch(v -> !v.available()))
          throw conflict("Remove unavailable rentals before saving this comparison.");
        repository.editComparison(id, name(r.name()), r.listingIds(), now);
      }
      default -> throw missing();
    }
    record(a, r, action, id);
    return read(a, token, kind, id);
  }

  @Transactional
  public DiscoveryMessageResponse delete(
      AuthenticatedUser a, String token, String kind, UUID id, DiscoveryCommandRequest r) {
    actor(a, token, true);
    String action = "DISCOVERY_" + kind.toUpperCase(Locale.ROOT) + "_DELETED";
    if (replay(a, r, action, id) != null)
      return new DiscoveryMessageResponse(id, "Saved record removed.");
    var old = get(a.id(), kind, id, true);
    version(old, r.version());
    repository.delete(a.id(), kind, id);
    record(a, r, action, id);
    return new DiscoveryMessageResponse(id, "Saved record removed.");
  }

  @Transactional
  public DiscoveryMessageResponse markRead(
      AuthenticatedUser a, String token, UUID id, DiscoveryCommandRequest r) {
    actor(a, token, true);
    String action = "DISCOVERY_ALERT_READ";
    if (replay(a, r, action, id) == null) {
      get(a.id(), "alerts", id, true);
      repository.read(a.id(), id, clock.instant());
      record(a, r, action, id);
    }
    return new DiscoveryMessageResponse(id, "Discovery update marked as read.");
  }
}
