package com.zaza.housing.repository;

import com.zaza.housing.dto.CreateListingRequest;
import com.zaza.housing.dto.ImageReferenceRequest;
import com.zaza.housing.dto.ListingSearchRequest;
import com.zaza.housing.dto.PublicListingResponse;
import com.zaza.housing.dto.PublishListingRequest;
import com.zaza.housing.dto.UpdateListingRequest;
import com.zaza.housing.dto.VideoReferenceRequest;
import com.zaza.housing.enums.ListingSort;
import com.zaza.housing.enums.ListingStatus;
import com.zaza.housing.exception.ApiException;
import com.zaza.housing.model.RentalListing;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ListingRepository {
  // Both search and detail use this same visibility policy, evaluated against current private
  // records.
  private static final String VISIBLE =
      """
      l.status = 'PUBLISHED' AND NOT EXISTS (SELECT 1 FROM listing_enforcements e WHERE e.listing_id=l.id AND e.status IN('REJECTED','SUSPENDED')) AND p.status = 'ACTIVE' AND u.status = 'ACTIVE' AND u.availability = 'AVAILABLE'
      AND l.source_property_version = p.version AND l.source_unit_version = u.version
      AND agency_management_access(l.publisher_organization_id,p.id)
      AND NOT EXISTS (SELECT 1 FROM listing_image_references r LEFT JOIN property_images i
          ON i.id = r.image_id AND i.property_id = r.property_id WHERE r.listing_id = l.id
          AND (i.id IS NULL OR i.status <> 'ACTIVE' OR i.version <> r.image_version))
      AND NOT EXISTS (SELECT 1 FROM listing_video_references r LEFT JOIN property_videos v
          ON v.id = r.video_id AND v.property_id = r.property_id WHERE r.listing_id = l.id
          AND (v.id IS NULL OR v.status <> 'ACTIVE' OR v.version <> r.video_version))
      """;
  private static final String FROM =
      """
      FROM rental_listings l JOIN properties p ON p.id = l.property_id
      JOIN property_units u ON u.id = l.unit_id AND u.property_id = p.id
      """;
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final RowMapper<RentalListing> listing;

  public List<java.util.Map<String, Object>> moderationSources(String q, int limit, int offset) {
    return jdbc.queryForList(
        "SELECT l.id,l.version,l.public_snapshot->>'title' AS title,l.public_snapshot->>'city' AS"
            + " city "
            + FROM
            + " WHERE "
            + VISIBLE
            + " AND (?='' OR strpos(lower(COALESCE(l.public_snapshot->>'title','')||'"
            + " '||COALESCE(l.public_snapshot->>'city','')),lower(?))>0) ORDER BY l.id LIMIT ?"
            + " OFFSET ?",
        q,
        q,
        limit,
        offset);
  }

  public ListingRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
    this.listing =
        (rs, row) ->
            new RentalListing(
                rs.getObject("id", UUID.class),
                rs.getObject("property_id", UUID.class),
                rs.getObject("unit_id", UUID.class),
                rs.getString("title"),
                rs.getString("description"),
                rs.getBoolean("show_exact_location"),
                ListingStatus.valueOf(rs.getString("status")),
                rs.getLong("version"),
                rs.getObject("publisher_organization_id", UUID.class),
                rs.getObject("source_property_version", Long.class),
                rs.getObject("source_unit_version", Long.class),
                rs.getBoolean("publicly_visible"),
                rs.getString("public_snapshot") == null
                    ? null
                    : json.readValue(rs.getString("public_snapshot"), PublicListingResponse.class),
                rs.getTimestamp("published_at") == null
                    ? null
                    : rs.getTimestamp("published_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
  }

  public UUID create(UUID property, UUID actor, CreateListingRequest request) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO rental_listings(id, property_id, unit_id, title, description, show_exact_location, created_by)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        property,
        request.unitId(),
        request.details().title().trim(),
        request.details().description().trim(),
        request.details().showExactLocation(),
        actor);
    return id;
  }

  public Optional<RentalListing> find(UUID property, UUID id) {
    return jdbc
        .query(
            "SELECT l.*, COALESCE(("
                + VISIBLE
                + "), false) AS publicly_visible "
                + FROM
                + " WHERE l.property_id = ? AND l.id = ?",
            listing,
            property,
            id)
        .stream()
        .findFirst();
  }

  public Optional<RentalListing> applicationSource(UUID id) {
    return jdbc
        .query(
            "SELECT l.*, true AS publicly_visible " + FROM + " WHERE " + VISIBLE + " AND l.id = ?",
            listing,
            id)
        .stream()
        .findFirst();
  }

  public List<PublicListingResponse> agencyMatches(
      UUID org, UUID actor, com.zaza.housing.dto.AgencyClientDetails d, int limit, int offset) {
    var args = new ArrayList<Object>();
    String sql =
        "SELECT l.public_snapshot "
            + FROM
            + " WHERE "
            + VISIBLE
            + " AND agency_property_access(?,p.id) AND (EXISTS(SELECT 1 FROM"
            + " organization_memberships m WHERE m.organization_id=? AND m.user_id=? AND m.role"
            + " IN('OWNER','ADMIN')) OR EXISTS(SELECT 1 FROM agency_property_assignments a WHERE"
            + " a.agency_id=? AND a.property_id=p.id AND a.agent_id=? AND a.active)) AND"
            + " l.public_snapshot->>'countryCode'=? AND lower(l.public_snapshot->>'city')=lower(?)"
            + " AND l.public_snapshot->>'currency'=?";
    args.addAll(List.of(org, org, actor, org, actor, d.countryCode(), d.city(), d.currency()));
    if (d.minBudget() != null) {
      sql += " AND (l.public_snapshot->>'monthlyRent')::numeric>=?";
      args.add(d.minBudget());
    }
    if (d.maxBudget() != null) {
      sql += " AND (l.public_snapshot->>'monthlyRent')::numeric<=?";
      args.add(d.maxBudget());
    }
    if (d.minBedrooms() != null) {
      sql += " AND (l.public_snapshot->>'bedrooms')::int>=?";
      args.add(d.minBedrooms());
    }
    sql += " ORDER BY l.published_at DESC,l.id LIMIT ? OFFSET ?";
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql,
        (rs, n) -> json.readValue(rs.getString("public_snapshot"), PublicListingResponse.class),
        args.toArray());
  }

  public List<RentalListing> list(UUID property, int limit, int offset) {
    return jdbc.query(
        "SELECT l.*, COALESCE(("
            + VISIBLE
            + "), false) AS publicly_visible "
            + FROM
            + " WHERE l.property_id = ? ORDER BY l.created_at, l.id LIMIT ? OFFSET ?",
        listing,
        property,
        limit,
        offset);
  }

  public void update(UUID property, UUID id, UpdateListingRequest request) {
    checkUpdated(
        jdbc.update(
            """
            UPDATE rental_listings SET title = ?, description = ?, show_exact_location = ?,
                status = CASE WHEN status = 'PUBLISHED' THEN 'WITHDRAWN' ELSE status END,
                version = version + 1, updated_at = clock_timestamp()
            WHERE property_id = ? AND id = ? AND version = ?
            """,
            request.details().title().trim(),
            request.details().description().trim(),
            request.details().showExactLocation(),
            property,
            id,
            request.version()));
  }

  public void publish(
      UUID property,
      UUID id,
      UUID organization,
      PublishListingRequest request,
      PublicListingResponse snapshot) {
    checkUpdated(
        jdbc.update(
            """
            UPDATE rental_listings SET status = 'PUBLISHED', publisher_organization_id = ?,
                source_property_version = ?, source_unit_version = ?, public_snapshot = CAST(? AS jsonb),
                published_at = ?, version = version + 1, updated_at = clock_timestamp()
            WHERE property_id = ? AND id = ? AND version = ?
            """,
            organization,
            request.propertyVersion(),
            request.unitVersion(),
            json.writeValueAsString(snapshot),
            Timestamp.from(snapshot.publishedAt()),
            property,
            id,
            request.version()));
  }

  public void withdraw(UUID property, UUID id, long version) {
    checkUpdated(
        jdbc.update(
            """
            UPDATE rental_listings SET status = 'WITHDRAWN', version = version + 1, updated_at = clock_timestamp()
            WHERE property_id = ? AND id = ? AND version = ?
            """,
            property,
            id,
            version));
  }

  public Optional<PublicListingResponse> publicListing(UUID id) {
    return jdbc
        .query(
            "SELECT l.public_snapshot " + FROM + " WHERE " + VISIBLE + " AND l.id = ?",
            (rs, row) ->
                json.readValue(rs.getString("public_snapshot"), PublicListingResponse.class),
            id)
        .stream()
        .findFirst();
  }

  public void replaceImageReferences(
      UUID property, UUID listing, List<ImageReferenceRequest> images) {
    jdbc.update("DELETE FROM listing_image_references WHERE listing_id = ?", listing);
    for (int index = 0; index < images.size(); index++) {
      var image = images.get(index);
      jdbc.update(
          "INSERT INTO listing_image_references(listing_id, property_id, image_id, image_version,"
              + " position) VALUES (?, ?, ?, ?, ?)",
          listing,
          property,
          image.id(),
          image.version(),
          index);
    }
  }

  public void replaceVideoReferences(
      UUID property, UUID listing, List<VideoReferenceRequest> videos) {
    jdbc.update("DELETE FROM listing_video_references WHERE listing_id = ?", listing);
    for (int index = 0; index < videos.size(); index++) {
      var video = videos.get(index);
      jdbc.update(
          "INSERT INTO listing_video_references(listing_id, property_id, video_id, video_version,"
              + " position) VALUES (?, ?, ?, ?, ?)",
          listing,
          property,
          video.id(),
          video.version(),
          index);
    }
  }

  public List<PublicListingResponse> search(ListingSearchRequest query, int limit, int offset) {
    StringBuilder sql = new StringBuilder("SELECT l.public_snapshot " + FROM + " WHERE " + VISIBLE);
    List<Object> args = new ArrayList<>();
    if (query.q() != null) {
      sql.append(" AND l.search_document @@ plainto_tsquery('simple', ?)");
      args.add(query.q().trim());
    }
    if (query.city() != null) {
      sql.append(" AND lower(l.public_snapshot ->> 'city') = lower(?)");
      args.add(query.city().trim());
    }
    if (query.countryCode() != null) {
      sql.append(" AND l.public_snapshot ->> 'countryCode' = ?");
      args.add(query.countryCode().toUpperCase(Locale.ROOT));
    }
    if (query.type() != null) {
      sql.append(" AND l.public_snapshot ->> 'propertyType' = ?");
      args.add(query.type().name());
    }
    if (query.currency() != null) {
      sql.append(" AND l.public_snapshot ->> 'currency' = ?");
      args.add(query.currency().toUpperCase(Locale.ROOT));
    }
    if (query.minRent() != null) {
      sql.append(" AND (l.public_snapshot ->> 'monthlyRent')::numeric >= ?");
      args.add(query.minRent());
    }
    if (query.maxRent() != null) {
      sql.append(" AND (l.public_snapshot ->> 'monthlyRent')::numeric <= ?");
      args.add(query.maxRent());
    }
    if (query.minBedrooms() != null) {
      sql.append(" AND (l.public_snapshot ->> 'bedrooms')::int >= ?");
      args.add(query.minBedrooms());
    }
    if (query.maxBedrooms() != null) {
      sql.append(" AND (l.public_snapshot ->> 'bedrooms')::int <= ?");
      args.add(query.maxBedrooms());
    }
    ListingSort sort = query.sort() == null ? ListingSort.NEWEST : query.sort();
    sql.append(
        switch (sort) {
          case NEWEST -> " ORDER BY l.published_at DESC, l.id";
          case PRICE_ASC -> " ORDER BY (l.public_snapshot ->> 'monthlyRent')::numeric ASC, l.id";
          case PRICE_DESC -> " ORDER BY (l.public_snapshot ->> 'monthlyRent')::numeric DESC, l.id";
        });
    sql.append(" LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(
        sql.toString(),
        (rs, row) -> json.readValue(rs.getString("public_snapshot"), PublicListingResponse.class),
        args.toArray());
  }

  private static void checkUpdated(int count) {
    if (count != 1)
      throw new ApiException(
          HttpStatus.CONFLICT, "The listing has changed. Reload it before saving.");
  }
}
