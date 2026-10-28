package com.zaza.housing;

import static org.assertj.core.api.Assertions.*;

import com.zaza.housing.service.DiscoveryAlertService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class DiscoveryWorkspaceTests extends AdministrationTestSupport {
  static final String PUBLIC = "/api/v1/discovery", SAVED = "/api/v1/me/discovery";
  @Autowired DiscoveryAlertService alerts;

  Map<String, Object> request() {
    return new HashMap<>(Map.of("requestId", UUID.randomUUID()));
  }

  Map<String, Object> edit(JsonNode row) {
    var r = request();
    r.put("version", row.get("version").asLong());
    return r;
  }

  ListingFixture rental(String word) throws Exception {
    var f = listing();
    jdbc.update(
        "UPDATE rental_listings SET"
            + " public_snapshot=jsonb_set(public_snapshot,'{title}',to_jsonb(?::text)) WHERE id=?",
        word,
        UUID.fromString(f.listing()));
    return f;
  }

  void point(ListingFixture f, double lat, double lng) {
    jdbc.update(
        "UPDATE rental_listings SET"
            + " public_snapshot=jsonb_set(jsonb_set(public_snapshot,'{latitude}',to_jsonb(?::float8)),'{longitude}',to_jsonb(?::float8))"
            + " WHERE id=?",
        lat,
        lng,
        UUID.fromString(f.listing()));
  }

  JsonNode search(String filters) throws Exception {
    return ok("GET", PUBLIC + "/search?" + filters, null, null, 200);
  }

  JsonNode save(Account a, String kind, Object body) throws Exception {
    return ok("POST", SAVED + "/" + kind, body, a.token(), 200);
  }

  Map<String, Object> savedItem(String id) {
    var r = request();
    r.put("kind", "PROPERTY");
    r.put("targetId", id);
    return r;
  }

  JsonNode savedSearch(Account a, String word, String frequency) throws Exception {
    var r = request();
    r.put("name", "My private home search");
    r.put("frequency", frequency);
    r.put("query", Map.of("kind", "PROPERTY", "q", word));
    return save(a, "searches", r);
  }

  void due(JsonNode row) {
    jdbc.update(
        "UPDATE discovery_saved_searches SET next_scan_at=now()-interval '1 second' WHERE id=?",
        UUID.fromString(row.get("id").asText()));
    alerts.scan(UUID.fromString(row.get("id").asText()));
  }

  @Test
  void cityOnlyMapNeverUsesPrivateCoordinatesForPinsOrRadiusMatching() throws Exception {
    String word = UUID.randomUUID().toString();
    var f = rental(word);
    jdbc.update(
        "UPDATE properties SET latitude=0.1,longitude=40.2 WHERE id=?",
        UUID.fromString(f.property()));
    var row = search("q=" + word).get("items").get(0);
    assertThat(row.get("locationPrecision").asText()).isEqualTo("AREA");
    assertThat(row.get("latitude").asDouble()).isEqualTo(-1.28333);
    assertThat(row.get("listing").get("latitude").isNull()).isTrue();
    assertThat(row.toString()).doesNotContain("40.2");
    assertThat(search("q=" + word + "&latitude=0.1&longitude=40.2&radiusKm=1").get("items").size())
        .isZero();
  }

  @Test
  void exactPublicLocationsSupportDistanceAndDatelineViewports() throws Exception {
    String word = UUID.randomUUID().toString();
    var a = rental(word);
    var b = rental(word);
    point(a, 0, 179.8);
    point(b, 0, -179.8);
    var data = search("q=" + word + "&north=1&south=-1&west=179&east=-179");
    assertThat(data.get("items").size()).isEqualTo(2);
    assertThat(data.get("mappedCount").asInt()).isEqualTo(2);
    assertThat(
            search("q=" + word + "&latitude=0&longitude=179.8&radiusKm=10&sort=DISTANCE")
                .get("items")
                .size())
        .isEqualTo(1);
  }

  @Test
  void invalidGeographyAndMixedCurrencyFiltersAreRejected() throws Exception {
    for (String query :
        List.of(
            "latitude=1",
            "latitude=91&longitude=1",
            "latitude=NaN&longitude=1",
            "radiusKm=20",
            "latitude=1&longitude=1&radiusKm=501",
            "north=0&south=1&east=1&west=0",
            "minRent=5",
            "currency=KES&minRent=10&maxRent=2",
            "limit=101",
            "kind=OTHER",
            "kind=PROVIDER&latitude=1&longitude=1&radiusKm=2"))
      ok("GET", PUBLIC + "/search?" + query, null, null, 400);
  }

  @Test
  void publicPlaceSearchIsAttributedAndPublicRoutesAreGuestReadable() throws Exception {
    var places = ok("GET", PUBLIC + "/places?q=Nairobi&countryCode=KE", null, null, 200);
    assertThat(places.get(0).get("source").asText()).contains("GeoNames");
    assertThat(places.get(0).get("name").asText()).isEqualTo("Nairobi");
    ok("GET", PUBLIC + "/places?q=x", null, null, 400);
    var config = ok("GET", PUBLIC + "/map", null, null, 200);
    assertThat(config.get("tileTemplate").asText()).startsWith("/api/v1/discovery/tiles/");
    assertThat(config.get("attribution").asText()).contains("OpenStreetMap");
  }

  @Test
  void mapAndSavedItemsRespectSuspensionAndWithdrawal() throws Exception {
    var a = user();
    String word = UUID.randomUUID().toString();
    var f = rental(word);
    var item = save(a, "items", savedItem(f.listing()));
    jdbc.update(
        "UPDATE rental_listings SET status='WITHDRAWN' WHERE id=?", UUID.fromString(f.listing()));
    assertThat(search("q=" + word).get("items").size()).isZero();
    var hidden = ok("GET", SAVED + "/items/" + item.get("id").asText(), null, a.token(), 200);
    assertThat(hidden.get("available").asBoolean()).isFalse();
    assertThat(hidden.get("item").isNull()).isTrue();
    assertThat(hidden.toString()).doesNotContain(word, "Review property", "Test address");
  }

  @Test
  void favouritesArePrivateVersionedAndAnExactRetryCreatesOneRecord() throws Exception {
    var a = user();
    var b = user();
    var f = listing();
    var r = savedItem(f.listing());
    r.put("folder", "Presentation");
    r.put("note", "Private shortlist note");
    var saved = save(a, "items", r);
    String id = saved.get("id").asText();
    assertThat(save(a, "items", r).get("id").asText()).isEqualTo(id);
    ok("GET", SAVED + "/items/" + id, null, b.token(), 404);
    ok("GET", SAVED + "/items", null, null, 401);
    assertThat(ok("GET", SAVED + "/items?folder=Presentation", null, a.token(), 200).size())
        .isEqualTo(1);
    var update = edit(saved);
    update.put("folder", "Finalists");
    update.put("note", "Reviewed for a viewing");
    saved = ok("PUT", SAVED + "/items/" + id, update, a.token(), 200);
    assertThat(saved.get("version").asInt()).isEqualTo(1);
    var stale = new HashMap<>(update);
    stale.put("requestId", UUID.randomUUID());
    ok("PUT", SAVED + "/items/" + id, stale, a.token(), 409);
    update.put("note", "Changed payload");
    ok("PUT", SAVED + "/items/" + id, update, a.token(), 409);
  }

  @Test
  void duplicateSaveKeepsOriginalNotesAndDeleteCanBeRetried() throws Exception {
    var a = user();
    var f = listing();
    var original = savedItem(f.listing());
    original.put("note", "Keep my original private note.");
    var item = save(a, "items", original);
    var duplicate = savedItem(f.listing());
    duplicate.put("note", "Must not overwrite");
    assertThat(save(a, "items", duplicate).get("note").asText())
        .isEqualTo("Keep my original private note.");
    var r = edit(item);
    String path = SAVED + "/items/" + item.get("id").asText() + "/delete";
    ok("POST", path, r, a.token(), 200);
    ok("POST", path, r, a.token(), 200);
    ok("GET", SAVED + "/items/" + item.get("id").asText(), null, a.token(), 404);
  }

  @Test
  void savedSearchBaselinesExistingMatchesAndDeliversNewMatchesOnce() throws Exception {
    var a = user();
    String word = UUID.randomUUID().toString();
    rental(word);
    var search = savedSearch(a, word, "HOURLY");
    due(search);
    assertThat(ok("GET", SAVED + "/alerts", null, a.token(), 200).size()).isZero();
    var fresh = rental(word);
    due(search);
    var notices = ok("GET", SAVED + "/alerts", null, a.token(), 200);
    assertThat(notices.size()).isEqualTo(1);
    assertThat(notices.get(0).get("targetId").asText()).isEqualTo(fresh.listing());
    due(search);
    assertThat(ok("GET", SAVED + "/alerts", null, a.token(), 200).size()).isEqualTo(1);
  }

  @Test
  void searchEditsRebaselineAndPauseSuppressesAlerts() throws Exception {
    var a = user();
    String old = UUID.randomUUID().toString(), next = UUID.randomUUID().toString();
    var search = savedSearch(a, old, "HOURLY");
    rental(next);
    var r = edit(search);
    r.put("name", "Changed search");
    r.put("frequency", "DAILY");
    r.put("query", Map.of("kind", "PROPERTY", "q", next));
    String id = search.get("id").asText();
    search = ok("PUT", SAVED + "/searches/" + id, r, a.token(), 200);
    due(search);
    assertThat(ok("GET", SAVED + "/alerts", null, a.token(), 200).size()).isZero();
    r = edit(search);
    r.put("name", "Paused");
    r.put("frequency", "OFF");
    r.put("query", Map.of("kind", "PROPERTY", "q", next));
    search = ok("PUT", SAVED + "/searches/" + id, r, a.token(), 200);
    rental(next);
    due(search);
    assertThat(ok("GET", SAVED + "/alerts", null, a.token(), 200).size()).isZero();
  }

  @Test
  void alertReadIsScopedAndUnavailableTargetsDoNotExposeSnapshots() throws Exception {
    var a = user();
    var b = user();
    String word = UUID.randomUUID().toString();
    var search = savedSearch(a, word, "HOURLY");
    var f = rental(word);
    due(search);
    var alert = ok("GET", SAVED + "/alerts", null, a.token(), 200).get(0);
    String id = alert.get("id").asText();
    ok("POST", SAVED + "/alerts/" + id + "/read", request(), b.token(), 404);
    jdbc.update(
        "UPDATE rental_listings SET status='WITHDRAWN' WHERE id=?", UUID.fromString(f.listing()));
    alert = ok("GET", SAVED + "/alerts/" + id, null, a.token(), 200);
    assertThat(alert.get("available").asBoolean()).isFalse();
    assertThat(alert.get("item").isNull()).isTrue();
    ok("POST", SAVED + "/alerts/" + id + "/read", request(), a.token(), 200);
  }

  @Test
  void comparisonUsesLivePublicRecordsAndNeverConvertsCurrencies() throws Exception {
    var a = user();
    var outsider = user();
    var f = listing();
    var g = listing();
    jdbc.update(
        "UPDATE rental_listings SET"
            + " public_snapshot=jsonb_set(public_snapshot,'{currency}',to_jsonb('USD'::text)) WHERE"
            + " id=?",
        UUID.fromString(g.listing()));
    var r = request();
    r.put("name", "Viewing finalists");
    r.put("listingIds", List.of(f.listing(), g.listing()));
    var board = save(a, "comparisons", r);
    assertThat(board.get("comparableCurrency").asBoolean()).isFalse();
    String id = board.get("id").asText();
    ok("GET", SAVED + "/comparisons/" + id, null, outsider.token(), 404);
    jdbc.update(
        "UPDATE rental_listings SET status='WITHDRAWN' WHERE id=?", UUID.fromString(f.listing()));
    board = ok("GET", SAVED + "/comparisons/" + id, null, a.token(), 200);
    assertThat(board.get("rows").get(0).get("available").asBoolean()).isFalse();
    assertThat(board.get("rows").get(0).get("item").isNull()).isTrue();
    ok("GET", PUBLIC + "/comparison?ids=" + g.listing() + "," + g.listing(), null, null, 400);
  }

  @Test
  void disabledAccountsCannotGenerateSavedSearchAlerts() throws Exception {
    var a = user();
    String word = UUID.randomUUID().toString();
    var search = savedSearch(a, word, "HOURLY");
    rental(word);
    jdbc.update("UPDATE app_users SET active=false WHERE id=?", a.id());
    due(search);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM discovery_alerts WHERE user_id=?", Long.class, a.id()))
        .isZero();
    ok("GET", SAVED + "/searches", null, a.token(), 401);
  }

  @Test
  void providerDiscoveryUsesPublishedServiceAreasAndActiveOffers() throws Exception {
    var owner = user();
    String org =
        ok(
                "POST",
                "/api/v1/organizations",
                Map.of("name", "Discovery provider", "type", "SERVICE_PROVIDER"),
                owner.token(),
                201)
            .get("id")
            .asText();
    String profile = "/api/v1/organizations/" + org + "/service-profile";
    String word = UUID.randomUUID().toString();
    ok(
        "POST",
        profile,
        Map.of(
            "displayName",
            word,
            "description",
            "Registered provider serving the published selected area.",
            "areas",
            List.of(Map.of("countryCode", "KE", "city", "*"))),
        owner.token(),
        200);
    var offer =
        ok(
            "POST",
            profile + "/offers",
            Map.of(
                "category",
                "PLUMBING",
                "title",
                "Inspection and repair",
                "description",
                "Inspection and repair scope to be agreed in a job quote.",
                "priceUnit",
                "QUOTE_REQUIRED",
                "currency",
                "KES"),
            owner.token(),
            200);
    ok(
        "POST",
        profile + "/offers/" + offer.get("id").asText() + "/state",
        Map.of("version", 0, "status", "ACTIVE"),
        owner.token(),
        200);
    ok("POST", profile + "/state", Map.of("version", 0, "status", "ACTIVE"), owner.token(), 200);
    var data =
        search(
            "kind=PROVIDER&q="
                + word
                + "&countryCode=KE&latitude=-1.28&longitude=36.82&radiusKm=10&category=PLUMBING");
    assertThat(data.get("items").size()).isEqualTo(1);
    assertThat(data.get("items").get(0).get("locationPrecision").asText()).isEqualTo("COVERAGE");
    assertThat(data.get("items").get(0).get("distanceKm").isNull()).isTrue();
    assertThat(search("kind=PROVIDER&q=" + word + "&category=CLEANING").get("items").size())
        .isZero();
    var r = request();
    r.put("kind", "PROVIDER");
    r.put("targetId", org);
    var saved = save(owner, "items", r);
    ok("POST", profile + "/state", Map.of("version", 1, "status", "PAUSED"), owner.token(), 200);
    assertThat(
            ok("GET", SAVED + "/items/" + saved.get("id").asText(), null, owner.token(), 200)
                .get("available")
                .asBoolean())
        .isFalse();
  }

  @Test
  void declaredAmenitiesStayPrivateUntilReviewedPublication() throws Exception {
    var f = listing();
    String path =
        "/api/v1/organizations/"
            + f.org()
            + "/properties/"
            + f.property()
            + "/listings/"
            + f.listing();
    var updated =
        ok(
            "PUT",
            path,
            Map.of(
                "version",
                1,
                "details",
                Map.of(
                    "title",
                    "Amenities rental",
                    "description",
                    "A rental with explicitly declared public amenities for discovery.",
                    "showExactLocation",
                    false,
                    "amenities",
                    List.of("Parking", "Lift", "Parking"))),
            f.owner().token(),
            200);
    assertThat(updated.get("amenities").size()).isEqualTo(2);
    ok("GET", "/api/v1/listings/" + f.listing(), null, null, 404);
    ok(
        "POST",
        path + "/publish",
        Map.of(
            "version",
            2,
            "propertyVersion",
            1,
            "unitVersion",
            0,
            "images",
            List.of(Map.of("id", f.image(), "version", 0))),
        f.owner().token(),
        200);
    var found = search("q=Amenities").get("items");
    assertThat(found.toString()).contains("Parking", "Lift");
    var bad =
        new HashMap<String, Object>(
            Map.of(
                "version",
                3,
                "details",
                Map.of(
                    "title",
                    "Amenities rental",
                    "description",
                    "A rental with explicitly declared public amenities for discovery.",
                    "showExactLocation",
                    false,
                    "amenities",
                    List.of(" "))));
    ok("PUT", path, bad, f.owner().token(), 400);
  }
}
