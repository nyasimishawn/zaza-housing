package com.zaza.housing.repository;

import com.zaza.housing.dto.ConversationEventResponse;
import com.zaza.housing.dto.ConversationInboxSummaryResponse;
import com.zaza.housing.dto.ConversationMessageResponse;
import com.zaza.housing.dto.ConversationResponse;
import com.zaza.housing.enums.ConversationChannel;
import com.zaza.housing.enums.ConversationKind;
import com.zaza.housing.enums.ConversationRole;
import com.zaza.housing.model.Conversation;
import com.zaza.housing.model.ConversationAccess;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;

  public ConversationRepository(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
  }

  static final String FROM =
      """
      FROM conversations c JOIN properties p ON p.id=c.property_id LEFT JOIN work_requests w ON w.id=c.work_id
      LEFT JOIN LATERAL (SELECT m.organization_id FROM organization_memberships m
      WHERE m.user_id=:actor AND agency_management_access(m.organization_id,p.id)
      AND (m.role IN ('OWNER','ADMIN') OR (c.kind='ENQUIRY' AND c.channel='SHARED'
      AND EXISTS(SELECT 1 FROM agency_leads al JOIN agency_property_assignments aa ON aa.agency_id=al.agency_id AND aa.property_id=al.property_id AND aa.agent_id=al.assigned_to
        WHERE al.agency_id=m.organization_id AND al.enquiry_id=c.id AND al.assigned_to=:actor AND aa.active AND agency_property_access(al.agency_id,al.property_id))))
      ORDER BY CASE WHEN m.organization_id=p.owner_organization_id THEN 0 ELSE 1 END LIMIT 1) pm ON true
      LEFT JOIN LATERAL (SELECT m.organization_id FROM organization_memberships m WHERE m.user_id=:actor
      AND m.organization_id=c.provider_id AND m.role IN ('OWNER','ADMIN')) pv ON true
      """;
  static final String CUSTOMER = "(c.customer_id=:actor AND c.channel='SHARED')";
  static final String MANAGER =
      "(pm.organization_id IS NOT NULL AND (c.work_id IS NULL OR w.kind='MAINTENANCE'))";
  static final String PROVIDER =
      "(pv.organization_id IS NOT NULL AND w.provider_id=c.provider_id AND"
          + " w.assignment_id=c.assignment_id AND w.status<>'ASSIGNED')";
  static final String ALLOW = "(" + CUSTOMER + " OR " + MANAGER + " OR " + PROVIDER + ")";
  static final String ROLE =
      "CASE WHEN "
          + CUSTOMER
          + " THEN 'CUSTOMER' WHEN "
          + MANAGER
          + " THEN 'MANAGER' ELSE 'PROVIDER' END";
  static final String ORGANIZATION =
      "CASE WHEN "
          + CUSTOMER
          + " THEN NULL WHEN "
          + MANAGER
          + " THEN pm.organization_id ELSE pv.organization_id END";
  static final String EXTRAS =
      """
      LEFT JOIN conversation_preferences pref ON pref.conversation_id=c.id AND pref.user_id=:actor
      LEFT JOIN LATERAL (SELECT count(*) AS unread FROM conversation_messages m WHERE m.conversation_id=c.id
      AND m.sequence>coalesce(pref.read_through,0) AND m.author_id<>:actor) unread ON true
      """;

  private static UUID id(ResultSet r, String s) throws SQLException {
    return r.getObject(s, UUID.class);
  }

  private static final RowMapper<Conversation> CONVERSATION =
      (r, n) ->
          new Conversation(
              id(r, "id"),
              ConversationKind.valueOf(r.getString("kind")),
              ConversationChannel.valueOf(r.getString("channel")),
              id(r, "listing_id"),
              id(r, "lease_id"),
              id(r, "work_id"),
              id(r, "property_id"),
              id(r, "unit_id"),
              id(r, "customer_id"),
              id(r, "provider_id"),
              id(r, "assignment_id"),
              r.getString("subject"),
              r.getString("status"),
              r.getLong("version"),
              r.getLong("last_sequence"),
              r.getTimestamp("created_at").toInstant(),
              r.getTimestamp("updated_at").toInstant());
  private static final RowMapper<ConversationResponse> RESPONSE =
      (r, n) -> {
        var c = CONVERSATION.mapRow(r, n);
        return new ConversationResponse(
            c.id(),
            c.kind(),
            c.channel(),
            c.listingId() != null ? c.listingId() : c.leaseId() != null ? c.leaseId() : c.workId(),
            c.subject(),
            c.status(),
            c.version(),
            c.lastSequence(),
            ConversationRole.valueOf(r.getString("actor_role")),
            r.getLong("unread"),
            r.getLong("read_through"),
            r.getBoolean("muted"),
            c.createdAt(),
            c.updatedAt());
      };
  private static final String SELECT =
      "SELECT c.*,"
          + ROLE
          + " AS actor_role,coalesce(pref.read_through,0) AS"
          + " read_through,coalesce(pref.muted,false) AS muted,unread.unread ";

  public Optional<Conversation> find(UUID id) {
    return jdbc.query("SELECT * FROM conversations WHERE id=?", CONVERSATION, id).stream()
        .findFirst();
  }

  public Optional<ConversationAccess> access(UUID actor, UUID id) {
    return named
        .query(
            "SELECT c.*,"
                + ROLE
                + " AS actor_role,"
                + ORGANIZATION
                + " AS actor_organization "
                + FROM
                + " WHERE "
                + ALLOW
                + " AND c.id=:id",
            Map.of("actor", actor, "id", id),
            (r, n) ->
                new ConversationAccess(
                    CONVERSATION.mapRow(r, n),
                    ConversationRole.valueOf(r.getString("actor_role")),
                    id(r, "actor_organization")))
        .stream()
        .findFirst();
  }

  public Optional<ConversationResponse> response(UUID actor, UUID id) {
    return named
        .query(
            SELECT + FROM + EXTRAS + " WHERE " + ALLOW + " AND c.id=:id",
            Map.of("actor", actor, "id", id),
            RESPONSE)
        .stream()
        .findFirst();
  }

  private String organizationFilter(UUID org) {
    return org == null
        ? ""
        : " AND (((c.work_id IS NULL OR w.kind='MAINTENANCE') AND agency_management_access(:org,p.id)) OR ("
            + PROVIDER
            + " AND c.provider_id=:org))";
  }

  public List<ConversationResponse> list(
      UUID actor,
      UUID org,
      ConversationKind kind,
      String status,
      boolean unreadOnly,
      int limit,
      int offset) {
    var args = new HashMap<String, Object>();
    args.put("actor", actor);
    args.put("limit", limit);
    args.put("offset", offset);
    String where = " WHERE " + ALLOW + organizationFilter(org);
    if (org != null) args.put("org", org);
    if (kind != null) {
      where += " AND c.kind=:kind";
      args.put("kind", kind.name());
    }
    if (status != null) {
      where += " AND c.status=:status";
      args.put("status", status);
    }
    if (unreadOnly) where += " AND unread.unread>0";
    return named.query(
        SELECT
            + FROM
            + EXTRAS
            + where
            + " ORDER BY c.updated_at DESC,c.id LIMIT :limit OFFSET :offset",
        args,
        RESPONSE);
  }

  public ConversationInboxSummaryResponse summary(UUID actor, UUID org) {
    var args = new HashMap<String, Object>();
    args.put("actor", actor);
    if (org != null) args.put("org", org);
    return named.queryForObject(
        "SELECT count(*) FILTER(WHERE c.status='OPEN') AS open,count(*) FILTER(WHERE"
            + " c.status='CLOSED') AS closed,count(*) FILTER(WHERE unread.unread>0) AS"
            + " conversations,coalesce(sum(unread.unread),0) AS unread "
            + FROM
            + EXTRAS
            + " WHERE "
            + ALLOW
            + organizationFilter(org),
        args,
        (r, n) ->
            new ConversationInboxSummaryResponse(
                r.getLong("open"),
                r.getLong("closed"),
                r.getLong("conversations"),
                r.getLong("unread")));
  }

  public Optional<UUID> manages(UUID actor, UUID property) {
    return jdbc
        .query(
            "SELECT m.organization_id FROM organization_memberships m JOIN properties p ON p.id=?"
                + " AND agency_management_access(m.organization_id,p.id)"
                + " WHERE m.user_id=? AND m.role IN ('OWNER','ADMIN') ORDER BY CASE WHEN"
                + " m.organization_id=p.owner_organization_id THEN 0 ELSE 1 END LIMIT 1",
            (r, n) -> id(r, "organization_id"),
            property,
            actor)
        .stream()
        .findFirst();
  }

  public void create(
      UUID id,
      ConversationKind kind,
      ConversationChannel channel,
      UUID listing,
      UUID lease,
      UUID work,
      UUID property,
      UUID unit,
      UUID customer,
      UUID provider,
      UUID assignment,
      String subject,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " conversations(id,kind,channel,listing_id,lease_id,work_id,property_id,unit_id,customer_id,provider_id,assignment_id,subject,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        kind.name(),
        channel.name(),
        listing,
        lease,
        work,
        property,
        unit,
        customer,
        provider,
        assignment,
        subject,
        Timestamp.from(now),
        Timestamp.from(now));
  }

  public Optional<UUID> enquiry(UUID listing, UUID customer) {
    return jdbc
        .query(
            "SELECT id FROM conversations WHERE listing_id=? AND customer_id=?",
            (r, n) -> id(r, "id"),
            listing,
            customer)
        .stream()
        .findFirst();
  }

  public Optional<UUID> lease(UUID lease) {
    return jdbc
        .query("SELECT id FROM conversations WHERE lease_id=?", (r, n) -> id(r, "id"), lease)
        .stream()
        .findFirst();
  }

  public Optional<UUID> work(UUID work, UUID assignment, ConversationChannel channel) {
    return jdbc
        .query(
            "SELECT id FROM conversations WHERE work_id=? AND assignment_id IS NOT DISTINCT FROM ?"
                + " AND channel=?",
            (r, n) -> id(r, "id"),
            work,
            assignment,
            channel.name())
        .stream()
        .findFirst();
  }

  public void touch(UUID id, Instant now) {
    jdbc.update(
        "UPDATE conversations SET version=version+1,updated_at=? WHERE id=?",
        Timestamp.from(now),
        id);
  }

  public void status(UUID id, String status) {
    jdbc.update("UPDATE conversations SET status=? WHERE id=?", status, id);
  }

  public long nextSequence(UUID id) {
    return jdbc.queryForObject(
        "UPDATE conversations SET last_sequence=last_sequence+1 WHERE id=? RETURNING last_sequence",
        Long.class,
        id);
  }

  public UUID message(Conversation c, UUID actor, ConversationRole role, String body, Instant now) {
    UUID id = UUID.randomUUID();
    long seq = nextSequence(c.id());
    jdbc.update(
        "INSERT INTO"
            + " conversation_messages(id,conversation_id,sequence,author_id,sender_role,body,created_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        id,
        c.id(),
        seq,
        actor,
        role.name(),
        body,
        Timestamp.from(now));
    return id;
  }

  public long recentMessages(UUID id, UUID actor, Instant since) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM conversation_messages WHERE conversation_id=? AND author_id=? AND"
            + " created_at>?",
        Long.class,
        id,
        actor,
        Timestamp.from(since));
  }

  public void lockEnquiryCustomer(UUID actor) {
    jdbc.query(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
        (r, n) -> 0,
        "enquiry-customer:" + actor);
  }

  public long recentEnquiries(UUID actor, Instant since) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM conversations WHERE kind='ENQUIRY' AND customer_id=? AND"
            + " created_at>?",
        Long.class,
        actor,
        Timestamp.from(since));
  }

  private static RowMapper<ConversationMessageResponse> messageMapper(boolean mineColumn) {
    return (r, n) ->
        new ConversationMessageResponse(
            id(r, "id"),
            r.getLong("sequence"),
            ConversationRole.valueOf(r.getString("sender_role")),
            mineColumn && r.getBoolean("mine"),
            r.getString("visible_body"),
            r.getBoolean("redacted"),
            r.getTimestamp("created_at").toInstant());
  }

  public List<ConversationMessageResponse> messages(
      UUID actor, UUID conversation, long after, int limit) {
    return named.query(
        "SELECT m.*,m.author_id=:actor AS mine,CASE WHEN red.message_id IS NULL THEN m.body ELSE"
            + " NULL END AS visible_body,red.message_id IS NOT NULL AS redacted "
            + FROM
            + " JOIN conversation_messages m ON m.conversation_id=c.id LEFT JOIN"
            + " conversation_message_redactions red ON red.message_id=m.id WHERE "
            + ALLOW
            + " AND c.id=:id AND m.sequence>:after ORDER BY m.sequence LIMIT :limit",
        Map.of("actor", actor, "id", conversation, "after", after, "limit", limit),
        messageMapper(true));
  }

  public Optional<Map<String, Object>> message(UUID conversation, UUID id) {
    return jdbc
        .queryForList(
            "SELECT m.*,EXISTS(SELECT 1 FROM conversation_message_redactions r WHERE"
                + " r.message_id=m.id) AS redacted FROM conversation_messages m WHERE"
                + " m.conversation_id=? AND m.id=?",
            conversation,
            id)
        .stream()
        .findFirst();
  }

  public ConversationMessageResponse evidence(UUID conversation, UUID id) {
    return jdbc.queryForObject(
        "SELECT m.*,body AS visible_body,EXISTS(SELECT 1 FROM conversation_message_redactions r"
            + " WHERE r.message_id=m.id) AS redacted FROM conversation_messages m WHERE"
            + " conversation_id=? AND id=?",
        messageMapper(false),
        conversation,
        id);
  }

  public void redact(UUID message, UUID actor, String reason, Instant now) {
    jdbc.update(
        "INSERT INTO conversation_message_redactions(message_id,actor_id,reason,created_at) VALUES"
            + " (?,?,?,?) ON CONFLICT DO NOTHING",
        message,
        actor,
        reason,
        Timestamp.from(now));
  }

  public void read(UUID conversation, UUID actor, long through) {
    jdbc.update(
        "INSERT INTO conversation_preferences(conversation_id,user_id,read_through) VALUES (?,?,?)"
            + " ON CONFLICT(conversation_id,user_id) DO UPDATE SET"
            + " read_through=greatest(conversation_preferences.read_through,excluded.read_through)",
        conversation,
        actor,
        through);
  }

  public void mute(UUID conversation, UUID actor, boolean muted) {
    jdbc.update(
        "INSERT INTO conversation_preferences(conversation_id,user_id,muted) VALUES (?,?,?) ON"
            + " CONFLICT(conversation_id,user_id) DO UPDATE SET muted=excluded.muted",
        conversation,
        actor,
        muted);
  }

  public Optional<Map<String, Object>> operation(UUID scope, UUID request) {
    return jdbc
        .queryForList(
            "SELECT * FROM conversation_operations WHERE scope_id=? AND request_id=?",
            scope,
            request)
        .stream()
        .findFirst();
  }

  public void operation(
      UUID scope, UUID request, UUID actor, String action, String fingerprint, UUID entity) {
    jdbc.update(
        "INSERT INTO"
            + " conversation_operations(scope_id,request_id,actor_id,action,fingerprint,entity_id)"
            + " VALUES (?,?,?,?,?,?)",
        scope,
        request,
        actor,
        action,
        fingerprint,
        entity);
  }

  public UUID event(
      UUID id, UUID actor, String type, UUID entity, String message, boolean shared, Instant now) {
    UUID event = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " conversation_events(id,conversation_id,actor_id,type,entity_id,message,shared,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        event,
        id,
        actor,
        type,
        entity,
        message,
        shared,
        Timestamp.from(now));
    return event;
  }

  public List<ConversationEventResponse> history(UUID actor, UUID id, int limit, int offset) {
    return named.query(
        "SELECT e.* "
            + FROM
            + " JOIN conversation_events e ON e.conversation_id=c.id WHERE "
            + ALLOW
            + " AND c.id=:id AND e.shared ORDER BY e.created_at DESC,e.id LIMIT :limit OFFSET"
            + " :offset",
        Map.of("actor", actor, "id", id, "limit", limit, "offset", offset),
        (r, n) ->
            new ConversationEventResponse(
                id(r, "id"),
                r.getString("type"),
                id(r, "entity_id"),
                r.getString("message"),
                r.getTimestamp("created_at").toInstant()));
  }

  public List<UUID> recipients(UUID conversation) {
    return jdbc.query(
        """
        SELECT DISTINCT u.id FROM conversations c JOIN properties p ON p.id=c.property_id LEFT JOIN work_requests w ON w.id=c.work_id
        JOIN app_users u ON u.active AND ((u.id=c.customer_id AND c.channel='SHARED') OR EXISTS(
        SELECT 1 FROM organization_memberships m WHERE m.user_id=u.id AND m.role IN ('OWNER','ADMIN') AND
        ((agency_management_access(m.organization_id,p.id) AND (w.id IS NULL OR w.kind='MAINTENANCE'))
        OR(m.organization_id=c.provider_id AND w.assignment_id=c.assignment_id AND w.provider_id=c.provider_id AND w.status<>'ASSIGNED'))))
        WHERE c.id=? AND NOT EXISTS(SELECT 1 FROM conversation_preferences pref WHERE pref.conversation_id=c.id AND pref.user_id=u.id AND pref.muted)
        """,
        (r, n) -> id(r, "id"),
        conversation);
  }

  public boolean participated(UUID conversation, UUID actor) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM conversation_messages WHERE conversation_id=? AND"
                + " author_id=?)",
            Boolean.class,
            conversation,
            actor));
  }
}
