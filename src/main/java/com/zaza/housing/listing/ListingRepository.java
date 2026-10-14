package com.zaza.housing.listing;

import com.zaza.housing.common.ApiException;
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
import static com.zaza.housing.listing.ListingRequests.*;

@Repository
public class ListingRepository {
    // Both search and detail use this same visibility policy, evaluated against current private records.
    private static final String VISIBLE = """
            l.status = 'PUBLISHED' AND p.status = 'ACTIVE' AND u.status = 'ACTIVE' AND u.availability = 'AVAILABLE'
            AND l.source_property_version = p.version AND l.source_unit_version = u.version
            AND (l.publisher_organization_id = p.owner_organization_id OR l.publisher_organization_id = p.manager_organization_id)
            """;
    private static final String FROM = """
            FROM rental_listings l JOIN properties p ON p.id = l.property_id
            JOIN property_units u ON u.id = l.unit_id AND u.property_id = p.id
            """;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<RentalListing> listing;

    public ListingRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        this.listing = (rs, row) -> new RentalListing(rs.getObject("id", UUID.class), rs.getObject("property_id", UUID.class),
                rs.getObject("unit_id", UUID.class), rs.getString("title"), rs.getString("description"),
                rs.getBoolean("show_exact_location"), ListingStatus.valueOf(rs.getString("status")), rs.getLong("version"),
                rs.getObject("publisher_organization_id", UUID.class), rs.getObject("source_property_version", Long.class),
                rs.getObject("source_unit_version", Long.class), rs.getBoolean("publicly_visible"),
                rs.getString("public_snapshot") == null ? null : json.readValue(rs.getString("public_snapshot"), PublicListing.class),
                rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    public UUID create(UUID property, UUID actor, Create request) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO rental_listings(id, property_id, unit_id, title, description, show_exact_location, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, property, request.unitId(), request.details().title().trim(), request.details().description().trim(),
                request.details().showExactLocation(), actor);
        return id;
    }

    public Optional<RentalListing> find(UUID property, UUID id) {
        return jdbc.query("SELECT l.*, COALESCE((" + VISIBLE + "), false) AS publicly_visible " + FROM
                + " WHERE l.property_id = ? AND l.id = ?", listing, property, id).stream().findFirst();
    }

    public List<RentalListing> list(UUID property, int limit, int offset) {
        return jdbc.query("SELECT l.*, COALESCE((" + VISIBLE + "), false) AS publicly_visible " + FROM
                + " WHERE l.property_id = ? ORDER BY l.created_at, l.id LIMIT ? OFFSET ?", listing, property, limit, offset);
    }

    public void update(UUID property, UUID id, Update request) {
        checkUpdated(jdbc.update("""
                UPDATE rental_listings SET title = ?, description = ?, show_exact_location = ?,
                    status = CASE WHEN status = 'PUBLISHED' THEN 'WITHDRAWN' ELSE status END,
                    version = version + 1, updated_at = clock_timestamp()
                WHERE property_id = ? AND id = ? AND version = ?
                """, request.details().title().trim(), request.details().description().trim(), request.details().showExactLocation(),
                property, id, request.version()));
    }

    public void publish(UUID property, UUID id, UUID organization, Publish request, PublicListing snapshot) {
        checkUpdated(jdbc.update("""
                UPDATE rental_listings SET status = 'PUBLISHED', publisher_organization_id = ?,
                    source_property_version = ?, source_unit_version = ?, public_snapshot = CAST(? AS jsonb),
                    published_at = ?, version = version + 1, updated_at = clock_timestamp()
                WHERE property_id = ? AND id = ? AND version = ?
                """, organization, request.propertyVersion(), request.unitVersion(), json.writeValueAsString(snapshot),
                Timestamp.from(snapshot.publishedAt()), property, id, request.version()));
    }

    public void withdraw(UUID property, UUID id, long version) {
        checkUpdated(jdbc.update("""
                UPDATE rental_listings SET status = 'WITHDRAWN', version = version + 1, updated_at = clock_timestamp()
                WHERE property_id = ? AND id = ? AND version = ?
                """, property, id, version));
    }

    public Optional<PublicListing> publicListing(UUID id) {
        return jdbc.query("SELECT l.public_snapshot " + FROM + " WHERE " + VISIBLE + " AND l.id = ?",
                (rs, row) -> json.readValue(rs.getString("public_snapshot"), PublicListing.class), id).stream().findFirst();
    }

    public List<PublicListing> search(Search query, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT l.public_snapshot " + FROM + " WHERE " + VISIBLE);
        List<Object> args = new ArrayList<>();
        if (query.q() != null) { sql.append(" AND l.search_document @@ plainto_tsquery('simple', ?)"); args.add(query.q().trim()); }
        if (query.city() != null) { sql.append(" AND lower(l.public_snapshot ->> 'city') = lower(?)"); args.add(query.city().trim()); }
        if (query.countryCode() != null) { sql.append(" AND l.public_snapshot ->> 'countryCode' = ?"); args.add(query.countryCode().toUpperCase(Locale.ROOT)); }
        if (query.type() != null) { sql.append(" AND l.public_snapshot ->> 'propertyType' = ?"); args.add(query.type().name()); }
        if (query.currency() != null) { sql.append(" AND l.public_snapshot ->> 'currency' = ?"); args.add(query.currency().toUpperCase(Locale.ROOT)); }
        if (query.minRent() != null) { sql.append(" AND (l.public_snapshot ->> 'monthlyRent')::numeric >= ?"); args.add(query.minRent()); }
        if (query.maxRent() != null) { sql.append(" AND (l.public_snapshot ->> 'monthlyRent')::numeric <= ?"); args.add(query.maxRent()); }
        if (query.minBedrooms() != null) { sql.append(" AND (l.public_snapshot ->> 'bedrooms')::int >= ?"); args.add(query.minBedrooms()); }
        if (query.maxBedrooms() != null) { sql.append(" AND (l.public_snapshot ->> 'bedrooms')::int <= ?"); args.add(query.maxBedrooms()); }
        ListingSort sort = query.sort() == null ? ListingSort.NEWEST : query.sort();
        sql.append(switch (sort) {
            case NEWEST -> " ORDER BY l.published_at DESC, l.id";
            case PRICE_ASC -> " ORDER BY (l.public_snapshot ->> 'monthlyRent')::numeric ASC, l.id";
            case PRICE_DESC -> " ORDER BY (l.public_snapshot ->> 'monthlyRent')::numeric DESC, l.id";
        });
        sql.append(" LIMIT ? OFFSET ?"); args.add(limit); args.add(offset);
        return jdbc.query(sql.toString(), (rs, row) -> json.readValue(rs.getString("public_snapshot"), PublicListing.class), args.toArray());
    }

    private static void checkUpdated(int count) {
        if (count != 1) throw new ApiException(HttpStatus.CONFLICT, "The listing has changed. Reload it before saving.");
    }
}
