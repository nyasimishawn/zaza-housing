package com.zaza.housing.repository;

import com.zaza.housing.dto.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class DiscoveryRepository {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper json;
  private final Clock clock;

  public DiscoveryRepository(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.json = json;
    this.clock = clock;
  }

  private String centre(String country, String city) {
    return " LEFT JOIN LATERAL(SELECT c.* FROM (SELECT d.*,count(*) OVER() n FROM discovery_places"
        + " d WHERE d.country_code="
        + country
        + " AND lower(d.name)=lower("
        + city
        + ")) c WHERE c.population>0 OR c.n=1 ORDER BY c.population DESC,c.id LIMIT 1) place ON"
        + " true ";
  }

  private String source(DiscoveryQuery q) {
    String sql;
    if (q.kind().equals("PROPERTY")) {
      sql =
          "WITH source AS ("
              + ListingRepository.discoverySource()
              + "), locations AS (SELECT s.*,COALESCE((body->>'latitude')::double"
              + " precision,place.latitude) lat,COALESCE((body->>'longitude')::double"
              + " precision,place.longitude) lng,CASE WHEN body->>'latitude' IS NOT NULL AND"
              + " body->>'longitude' IS NOT NULL THEN 'EXACT' WHEN place.id IS NOT NULL THEN 'AREA'"
              + " ELSE 'UNMAPPED' END location_precision FROM source s "
              + centre("s.body->>'countryCode'", "s.body->>'city'")
              + ")";
    } else {
      String source =
          ServiceProviderRepository.discoverySource().replace("?", "cast(:now as timestamptz)");
      sql =
          "WITH source AS ("
              + source
              + "), locations AS (SELECT s.*,CASE WHEN a->>'city'='*' AND"
              + " a->>'countryCode'=cast(:country as text) THEN"
              + " COALESCE(place.latitude,cast(:focusLat as double precision)) ELSE place.latitude"
              + " END lat,CASE WHEN a->>'city'='*' AND a->>'countryCode'=cast(:country as text)"
              + " THEN COALESCE(place.longitude,cast(:focusLng as double precision)) ELSE"
              + " place.longitude END lng,CASE WHEN a->>'city'='*' AND"
              + " a->>'countryCode'=cast(:country as text) AND (place.id IS NOT NULL OR"
              + " cast(:focusLat as double precision) IS NOT NULL) THEN 'COVERAGE' WHEN place.id IS"
              + " NOT NULL THEN 'AREA' ELSE 'UNMAPPED' END location_precision FROM source s CROSS"
              + " JOIN LATERAL jsonb_array_elements(s.body->'areas') a "
              + centre(
                  "a->>'countryCode'",
                  "CASE WHEN a->>'city'='*' THEN cast(:city as text) ELSE a->>'city' END")
              + " WHERE (cast(:country as text) IS NULL OR a->>'countryCode'=:country) AND"
              + " (cast(:city as text) IS NULL OR lower(a->>'city')=lower(:city) OR a->>'city'='*')"
              + " AND (cast(:category as text) IS NULL OR EXISTS(SELECT 1 FROM service_offers o"
              + " WHERE o.provider_id=s.id AND o.status='ACTIVE' AND o.category=:category)) AND"
              + " (NOT :verified OR s.verification_status='VERIFIED'))";
    }
    sql +=
        ", distances AS (SELECT locations.*,CASE WHEN cast(:focusLat as double precision) IS NOT"
            + " NULL AND lat IS NOT NULL AND lng IS NOT NULL THEN"
            + " 6371.0088*2*asin(sqrt(least(1.0,greatest(0.0,power(sin(radians(lat-cast(:focusLat"
            + " as double precision))/2),2)+cos(radians(lat))*cos(radians(cast(:focusLat as double"
            + " precision)))*power(sin(radians(lng-cast(:focusLng as double precision))/2),2)))))"
            + " END distance FROM locations), filtered AS (SELECT * FROM distances WHERE true";
    if (q.q() != null)
      sql +=
          " AND strpos(lower(COALESCE(body->>'title',body->>'displayName','')||'"
              + " '||COALESCE(body->>'description','')),lower(:q))>0";
    if (q.kind().equals("PROPERTY")) {
      if (q.city() != null) sql += " AND lower(body->>'city')=lower(:city)";
      if (q.countryCode() != null) sql += " AND body->>'countryCode'=:country";
      if (q.type() != null) sql += " AND body->>'propertyType'=:type";
      if (q.currency() != null) sql += " AND body->>'currency'=:currency";
      if (q.minRent() != null) sql += " AND (body->>'monthlyRent')::numeric>=:minRent";
      if (q.maxRent() != null) sql += " AND (body->>'monthlyRent')::numeric<=:maxRent";
      if (q.minBedrooms() != null) sql += " AND (body->>'bedrooms')::integer>=:minBedrooms";
      if (q.maxBedrooms() != null) sql += " AND (body->>'bedrooms')::integer<=:maxBedrooms";
    }
    if (q.radiusKm() != null) sql += " AND distance<=:radius";
    if (q.north() != null)
      sql +=
          " AND lat BETWEEN :south AND :north AND ((:west<=:east AND lng BETWEEN :west AND :east)"
              + " OR (:west>:east AND (lng>=:west OR lng<=:east)))";
    return sql
        + "), distinct_items AS (SELECT DISTINCT ON(id) * FROM filtered ORDER BY id,distance NULLS"
        + " LAST,lat NULLS LAST,lng NULLS LAST) SELECT * FROM distinct_items";
  }

  private MapSqlParameterSource parameters(DiscoveryQuery q) {
    Double lat = q.latitude(), lng = q.longitude();
    if (lat == null && q.north() != null) {
      lat = (q.north() + q.south()) / 2;
      lng = q.west() <= q.east() ? (q.west() + q.east()) / 2 : (q.west() + q.east() + 360) / 2;
      if (lng > 180) lng -= 360;
    }
    return new MapSqlParameterSource()
        .addValue("now", Timestamp.from(clock.instant()))
        .addValue("q", q.q())
        .addValue("city", q.city())
        .addValue("country", q.countryCode())
        .addValue("type", q.type())
        .addValue("currency", q.currency())
        .addValue("minRent", q.minRent())
        .addValue("maxRent", q.maxRent())
        .addValue("minBedrooms", q.minBedrooms())
        .addValue("maxBedrooms", q.maxBedrooms())
        .addValue("category", q.category())
        .addValue("verified", Boolean.TRUE.equals(q.verifiedOnly()))
        .addValue("focusLat", lat)
        .addValue("focusLng", lng)
        .addValue("radius", q.radiusKm())
        .addValue("north", q.north())
        .addValue("south", q.south())
        .addValue("east", q.east())
        .addValue("west", q.west());
  }

  private DiscoveryItemResponse row(ResultSet r, DiscoveryQuery q) throws SQLException {
    var property = q.kind().equals("PROPERTY");
    String body = r.getString("body");
    String precision = r.getString("location_precision");
    var categories =
        property ? List.<String>of() : List.of((String[]) r.getArray("categories").getArray());
    return new DiscoveryItemResponse(
        r.getObject("id", UUID.class),
        q.kind(),
        r.getLong("version"),
        property ? json.readValue(body, PublicListingResponse.class) : null,
        property ? null : json.readValue(body, PublicServiceProviderResponse.class),
        r.getObject("lat", Double.class),
        r.getObject("lng", Double.class),
        precision,
        precision.equals("COVERAGE") ? null : r.getObject("distance", Double.class),
        categories,
        property
            ? null
            : new VerificationSummaryResponse(
                r.getString("verification_status"),
                r.getString("verification_method"),
                r.getTimestamp("verification_valid_until") == null
                    ? null
                    : r.getTimestamp("verification_valid_until").toInstant()),
        property ? List.of(json.readValue(r.getString("amenities"), String[].class)) : List.of());
  }

  private String order(DiscoveryQuery q) {
    return switch (q.sort()) {
      case "PRICE_ASC" -> "(body->>'monthlyRent')::numeric,id";
      case "PRICE_DESC" -> "(body->>'monthlyRent')::numeric DESC,id";
      case "DISTANCE" -> "distance NULLS LAST,id";
      case "NAME" -> "lower(COALESCE(body->>'title',body->>'displayName')),id";
      default ->
          q.kind().equals("PROPERTY") ? "published_at DESC,id" : "lower(body->>'displayName'),id";
    };
  }

  public List<DiscoveryItemResponse> search(DiscoveryQuery q, int limit, int offset) {
    return named.query(
        source(q) + " ORDER BY " + order(q) + " LIMIT :limit OFFSET :offset",
        parameters(q).addValue("limit", limit).addValue("offset", offset),
        (r, n) -> row(r, q));
  }

  public DiscoveryItemResponse item(DiscoveryQuery q, UUID id) {
    return named
        .query(
            source(q) + " WHERE id=:target",
            parameters(q).addValue("target", id),
            (r, n) -> row(r, q))
        .stream()
        .findFirst()
        .orElse(null);
  }

  public void baseline(UUID id, UUID generation, DiscoveryQuery q) {
    named.update(
        "INSERT INTO discovery_search_seen(search_id,generation,target_id) SELECT"
            + " :search,:generation,id FROM ("
            + source(q)
            + ") matched ON CONFLICT DO NOTHING",
        parameters(q).addValue("search", id).addValue("generation", generation));
  }

  public List<UUID> unseen(UUID id, UUID generation, DiscoveryQuery q) {
    return named.query(
        "SELECT id FROM ("
            + source(q)
            + ") matched WHERE NOT EXISTS(SELECT 1 FROM discovery_search_seen v WHERE"
            + " v.search_id=:search AND v.generation=:generation AND v.target_id=matched.id) ORDER"
            + " BY id LIMIT 50",
        parameters(q).addValue("search", id).addValue("generation", generation),
        (r, n) -> r.getObject(1, UUID.class));
  }

  public List<DiscoveryPlaceResponse> places(String q, String country, int limit) {
    return jdbc.query(
        "SELECT * FROM discovery_places WHERE (?::text IS NULL OR country_code=?) AND"
            + " (strpos(lower(name),lower(?))>0 OR strpos(lower(aliases),lower(?))>0) ORDER BY CASE"
            + " WHEN lower(name)=lower(?) THEN 0 WHEN starts_with(lower(name),lower(?)) THEN 1 ELSE"
            + " 2 END,population DESC,id LIMIT ?",
        (r, n) ->
            new DiscoveryPlaceResponse(
                r.getLong("id"),
                r.getString("name"),
                r.getString("country_code"),
                r.getDouble("latitude"),
                r.getDouble("longitude"),
                r.getLong("population"),
                r.getString("source")),
        country,
        country,
        q,
        q,
        q,
        q,
        limit);
  }
}
