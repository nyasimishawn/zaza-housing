package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.repository.DiscoveryRepository;
import com.zaza.housing.util.Pagination;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class DiscoveryReadService {
  private final DiscoveryRepository repository;
  private final DiscoveryQueryService queries;
  private final PropertyOwnershipVerificationService ownership;

  public DiscoveryReadService(
      DiscoveryRepository repository,
      DiscoveryQueryService queries,
      PropertyOwnershipVerificationService ownership) {
    this.repository = repository;
    this.queries = queries;
    this.ownership = ownership;
  }

  public DiscoveryQuery defaults(String kind) {
    return queries.normalize(
        new DiscoveryQuery(
            kind, null, null, null, null, null, null, null, null, null, null, false, null, null,
            null, null, null, null, null, null, null, null),
        true);
  }

  public DiscoveryPageResponse search(DiscoveryQuery input) {
    var q = queries.normalize(input, false);
    Pagination.validate(q.limit(), q.offset());
    var rows = repository.search(q, q.limit() + 1, q.offset());
    var items = rows.stream().limit(q.limit()).map(this::verify).filter(Objects::nonNull).toList();
    int mapped =
        (int) items.stream().filter(r -> r.latitude() != null && r.longitude() != null).count();
    return new DiscoveryPageResponse(
        items,
        q.limit(),
        q.offset(),
        rows.size() > q.limit(),
        mapped,
        items.size() - mapped,
        "Area markers show public area centres, not private property or business addresses."
            + " Unmapped places remain available in list search.");
  }

  public DiscoveryItemResponse item(String kind, UUID id) {
    var row = repository.item(defaults(kind), id);
    return row == null ? null : verify(row);
  }

  public DiscoveryItemResponse matching(DiscoveryQuery q, UUID id) {
    var row = repository.item(q, id);
    return row == null ? null : verify(row);
  }

  private DiscoveryItemResponse verify(DiscoveryItemResponse r) {
    if (!r.kind().equals("PROPERTY")) return r;
    VerificationSummaryResponse v;
    try {
      v = ownership.publicStatus(r.id());
    } catch (com.zaza.housing.exception.ApiException e) {
      if (e.status().value() == 404) return null;
      throw e;
    }
    return new DiscoveryItemResponse(
        r.id(),
        r.kind(),
        r.version(),
        r.listing(),
        r.provider(),
        r.latitude(),
        r.longitude(),
        r.locationPrecision(),
        r.distanceKm(),
        r.categories(),
        v,
        r.amenities());
  }

  public List<DiscoveryPlaceResponse> places(String q, String country, int limit) {
    q = DiscoveryQueryService.clean(q);
    if (q == null || q.length() < 2 || q.length() > 100 || limit < 1 || limit > 20)
      throw DiscoveryQueryService.bad("Search places with 2–100 characters and limit 1–20.");
    if (country != null) {
      country = country.toUpperCase(Locale.ROOT);
      if (!Arrays.asList(Locale.getISOCountries()).contains(country))
        throw DiscoveryQueryService.bad("Use a valid country code.");
    }
    return repository.places(q, country, limit);
  }

  public List<DiscoveryComparisonRow> compare(List<UUID> ids) {
    if (ids == null || ids.isEmpty() || ids.size() > 4 || new HashSet<>(ids).size() != ids.size())
      throw DiscoveryQueryService.bad("Choose one to four distinct public rentals.");
    return ids.stream()
        .map(
            id -> {
              var item = item("PROPERTY", id);
              return new DiscoveryComparisonRow(id, item != null, item);
            })
        .toList();
  }

  public boolean currency(List<DiscoveryComparisonRow> rows) {
    return rows.size() >= 2
        && rows.stream().allMatch(DiscoveryComparisonRow::available)
        && rows.stream().map(r -> r.item().listing().currency()).distinct().count() == 1;
  }
}
