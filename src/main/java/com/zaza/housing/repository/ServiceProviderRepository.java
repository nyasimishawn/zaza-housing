package com.zaza.housing.repository;

import com.zaza.housing.dto.ProviderProfileDetails;
import com.zaza.housing.dto.ProviderProfileResponse;
import com.zaza.housing.dto.ServiceOfferDetails;
import com.zaza.housing.dto.ServiceOfferResponse;
import com.zaza.housing.dto.ServiceReviewResponse;
import com.zaza.housing.enums.ProviderStatus;
import com.zaza.housing.enums.ServiceCategory;
import com.zaza.housing.enums.ServiceOfferStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class ServiceProviderRepository {
  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final Clock clock;
  private final RowMapper<ServiceOfferResponse> offer;
  private final RowMapper<ProviderProfileResponse> profileMapper;
  private static final String PROFILES =
      """
      SELECT p.*,rating.review_count,rating.average_rating,
        CASE WHEN verification.valid_until IS NOT NULL THEN 'VERIFIED' ELSE 'NOT_VERIFIED' END verification_status,
        CASE WHEN verification.valid_until IS NOT NULL THEN 'MANUAL_DOCUMENT_REVIEW' END verification_method,
        verification.valid_until verification_valid_until
      FROM service_provider_profiles p
      CROSS JOIN LATERAL (
        SELECT max(v.valid_until) valid_until FROM verification_cases v
        JOIN verification_submissions s ON s.case_id=v.id AND s.id=v.shared_submission_id
        JOIN organizations organization ON organization.id=v.organization_id
        WHERE v.organization_id=p.organization_id AND v.kind='BUSINESS' AND v.status='VERIFIED' AND v.valid_until>?
        AND lower(btrim(organization.name))=lower(btrim(s.details->>'claimedName'))
        AND NOT EXISTS(SELECT 1 FROM verification_submission_documents evidence JOIN vault_documents d ON d.id=evidence.document_id
          WHERE evidence.case_id=v.id AND evidence.submission_id=v.shared_submission_id AND d.status<>'ACTIVE')
      ) verification
      CROSS JOIN LATERAL (
        SELECT count(*) AS review_count,round(avg(r.rating),2) AS average_rating FROM service_reviews r
        JOIN work_requests w ON w.id=r.work_id AND w.status='COMPLETED' AND w.assignment_id=r.assignment_id
        WHERE r.provider_id=p.organization_id
      ) rating
      """;

  static String discoverySource() {
    return "SELECT v.organization_id AS id,v.version,ARRAY(SELECT DISTINCT o.category FROM"
               + " service_offers o WHERE o.provider_id=v.organization_id AND o.status='ACTIVE'"
               + " ORDER BY o.category) AS"
               + " categories,jsonb_build_object('id',v.organization_id,'displayName',v.details->>'displayName','description',v.details->>'description','areas',v.details->'areas','verificationStatus',v.verification_status,'verificationMethod',v.verification_method,'verificationValidUntil',v.verification_valid_until,'reviewCount',v.review_count,'averageRating',v.average_rating)"
               + " AS body,v.verification_status,v.verification_method,v.verification_valid_until"
               + " FROM ("
        + PROFILES
        + " WHERE p.status='ACTIVE' AND EXISTS(SELECT 1 FROM service_offers o WHERE"
        + " o.provider_id=p.organization_id AND o.status='ACTIVE')) v";
  }

  public ServiceProviderRepository(JdbcTemplate jdbc, ObjectMapper json, Clock clock) {
    this.clock = clock;
    this.jdbc = jdbc;
    this.json = json;
    profileMapper =
        (r, n) ->
            new ProviderProfileResponse(
                r.getObject("organization_id", UUID.class),
                json.readValue(r.getString("details"), ProviderProfileDetails.class),
                ProviderStatus.valueOf(r.getString("status")),
                r.getLong("version"),
                r.getString("verification_status"),
                r.getLong("review_count"),
                r.getBigDecimal("average_rating"),
                r.getString("verification_method"),
                r.getTimestamp("verification_valid_until") == null
                    ? null
                    : r.getTimestamp("verification_valid_until").toInstant());
    offer =
        (r, n) ->
            new ServiceOfferResponse(
                r.getObject("id", UUID.class),
                r.getObject("provider_id", UUID.class),
                r.getLong("version"),
                ServiceOfferStatus.valueOf(r.getString("status")),
                json.readValue(r.getString("details"), ServiceOfferDetails.class));
  }

  public Optional<ProviderProfileResponse> profile(UUID id) {
    return jdbc
        .query(
            PROFILES + " WHERE p.organization_id=?",
            profileMapper,
            Timestamp.from(clock.instant()),
            id)
        .stream()
        .findFirst();
  }

  public void create(UUID id, ProviderProfileDetails details) {
    jdbc.update(
        "INSERT INTO service_provider_profiles(organization_id,details) VALUES (?,CAST(? AS"
            + " jsonb))",
        id,
        json.writeValueAsString(details));
  }

  public void update(UUID id, ProviderProfileDetails details) {
    jdbc.update(
        "UPDATE service_provider_profiles SET details=CAST(? AS jsonb),version=version+1 WHERE"
            + " organization_id=?",
        json.writeValueAsString(details),
        id);
  }

  public void status(UUID id, ProviderStatus status) {
    jdbc.update(
        "UPDATE service_provider_profiles SET status=?,version=version+1 WHERE organization_id=?",
        status.name(),
        id);
  }

  public UUID createOffer(UUID provider, ServiceOfferDetails details) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO service_offers(id,provider_id,category,details) VALUES (?,?,?,CAST(? AS"
            + " jsonb))",
        id,
        provider,
        details.category().name(),
        json.writeValueAsString(details));
    return id;
  }

  public void updateOffer(UUID id, ServiceOfferDetails details) {
    jdbc.update(
        "UPDATE service_offers SET category=?,details=CAST(? AS jsonb),version=version+1 WHERE"
            + " id=?",
        details.category().name(),
        json.writeValueAsString(details),
        id);
  }

  public void offerStatus(UUID id, ServiceOfferStatus status) {
    jdbc.update(
        "UPDATE service_offers SET status=?,version=version+1 WHERE id=?", status.name(), id);
  }

  public Optional<ServiceOfferResponse> offer(UUID id) {
    return jdbc.query("SELECT * FROM service_offers WHERE id=?", offer, id).stream().findFirst();
  }

  public List<ServiceOfferResponse> offers(
      UUID provider, boolean published, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM service_offers WHERE provider_id=?"
            + (published ? " AND status='ACTIVE'" : "")
            + " ORDER BY created_at,id LIMIT ? OFFSET ?",
        offer,
        provider,
        limit,
        offset);
  }

  public boolean hasActiveOffer(UUID provider) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM service_offers WHERE provider_id=? AND status='ACTIVE')",
            Boolean.class,
            provider));
  }

  public List<ProviderProfileResponse> directory(
      ServiceCategory category, String country, String city, int limit, int offset) {
    StringBuilder sql =
        new StringBuilder(
            PROFILES
                + " WHERE p.status='ACTIVE' AND"
                + " EXISTS(SELECT 1 FROM service_offers o WHERE o.provider_id=p.organization_id AND"
                + " o.status='ACTIVE'");
    var args = new ArrayList<Object>();
    args.add(Timestamp.from(clock.instant()));
    if (category != null) {
      sql.append(" AND o.category=?");
      args.add(category.name());
    }
    sql.append(")");
    if (country != null || city != null) {
      sql.append(" AND EXISTS(SELECT 1 FROM jsonb_array_elements(p.details->'areas') a WHERE true");
      if (country != null) {
        sql.append(" AND a->>'countryCode'=?");
        args.add(country);
      }
      if (city != null) {
        sql.append(" AND (lower(a->>'city')=lower(?) OR a->>'city'='*')");
        args.add(city);
      }
      sql.append(")");
    }
    sql.append(" ORDER BY lower(p.details->>'displayName'),p.organization_id LIMIT ? OFFSET ?");
    args.add(limit);
    args.add(offset);
    return jdbc.query(sql.toString(), profileMapper, args.toArray());
  }

  public List<ServiceReviewResponse> reviews(UUID provider, int limit, int offset) {
    return jdbc.query(
        "SELECT r.* FROM service_reviews r JOIN work_requests w ON w.id=r.work_id AND"
            + " w.status='COMPLETED' AND w.assignment_id=r.assignment_id WHERE r.provider_id=?"
            + " ORDER BY r.created_at DESC,r.id LIMIT ? OFFSET ?",
        (r, n) ->
            new ServiceReviewResponse(
                r.getObject("id", UUID.class),
                provider,
                r.getInt("rating"),
                r.getString("comment"),
                r.getTimestamp("created_at").toInstant()),
        provider,
        limit,
        offset);
  }
}
