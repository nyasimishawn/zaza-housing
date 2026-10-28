package com.zaza.housing.repository;

import com.zaza.housing.dto.AgencyAgentResponse;
import com.zaza.housing.dto.AgencyClientDetails;
import com.zaza.housing.dto.AgencyDashboardResponse;
import com.zaza.housing.dto.AgencyEnquiryResponse;
import com.zaza.housing.dto.AgencyLeadCreateRequest;
import com.zaza.housing.dto.AgencyPropertyResponse;
import com.zaza.housing.dto.AgencyTaskRequest;
import com.zaza.housing.dto.AgencyWorkflowChoice;
import com.zaza.housing.model.AgencyClient;
import com.zaza.housing.model.AgencyEvent;
import com.zaza.housing.model.AgencyLead;
import com.zaza.housing.model.AgencyPropertyAssignment;
import com.zaza.housing.model.AgencyTask;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class AgencyRepository {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper json;

  public AgencyRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.json = json;
  }

  public static final String MANAGER =
      "EXISTS(SELECT 1 FROM organization_memberships m WHERE m.organization_id=:org AND"
          + " m.user_id=:actor AND m.role IN('OWNER','ADMIN'))";
  public static final String LEAD_VISIBLE =
      """
       EXISTS(SELECT 1 FROM organization_memberships m WHERE m.organization_id=l.agency_id AND m.user_id=:actor AND
        (m.role IN('OWNER','ADMIN') OR (l.assigned_to=:actor AND (l.property_id IS NULL OR
          (agency_property_access(l.agency_id,l.property_id) AND EXISTS(SELECT 1 FROM agency_property_assignments a
            WHERE a.agency_id=l.agency_id AND a.property_id=l.property_id AND a.agent_id=:actor AND a.active))))))
      """;

  private Map<String, Object> args(UUID org, UUID actor) {
    var a = new HashMap<String, Object>();
    a.put("org", org);
    a.put("actor", actor);
    return a;
  }

  private static Timestamp time(Instant t) {
    return t == null ? null : Timestamp.from(t);
  }

  private RowMapper<AgencyClient> clientMapper() {
    return (r, n) ->
        new AgencyClient(
            r.getObject("id", UUID.class),
            r.getObject("agency_id", UUID.class),
            r.getObject("customer_user_id", UUID.class),
            json.readValue(r.getString("details"), AgencyClientDetails.class),
            r.getString("status"),
            r.getLong("version"),
            r.getTimestamp("created_at").toInstant(),
            r.getTimestamp("updated_at").toInstant());
  }

  private final RowMapper<AgencyLead> leadMapper =
      (r, n) ->
          new AgencyLead(
              r.getObject("id", UUID.class),
              r.getObject("agency_id", UUID.class),
              r.getObject("client_id", UUID.class),
              r.getString("title"),
              r.getString("stage"),
              r.getObject("assigned_to", UUID.class),
              r.getObject("property_id", UUID.class),
              r.getObject("listing_id", UUID.class),
              r.getObject("enquiry_id", UUID.class),
              r.getObject("viewing_id", UUID.class),
              r.getObject("application_id", UUID.class),
              r.getObject("lease_id", UUID.class),
              r.getLong("version"),
              r.getTimestamp("won_at") == null ? null : r.getTimestamp("won_at").toInstant(),
              r.getTimestamp("created_at").toInstant(),
              r.getTimestamp("updated_at").toInstant());
  private final RowMapper<AgencyTask> taskMapper =
      (r, n) ->
          new AgencyTask(
              r.getObject("id", UUID.class),
              r.getObject("agency_id", UUID.class),
              r.getObject("lead_id", UUID.class),
              r.getObject("assignee_id", UUID.class),
              r.getString("title"),
              r.getString("kind"),
              r.getTimestamp("due_at").toInstant(),
              r.getString("description"),
              r.getString("status"),
              r.getLong("version"),
              r.getTimestamp("created_at").toInstant(),
              r.getTimestamp("updated_at").toInstant());
  private final RowMapper<AgencyPropertyAssignment> assignmentMapper =
      (r, n) ->
          new AgencyPropertyAssignment(
              r.getObject("id", UUID.class),
              r.getObject("agency_id", UUID.class),
              r.getObject("property_id", UUID.class),
              r.getObject("agent_id", UUID.class),
              r.getBoolean("active"),
              r.getLong("version"),
              r.getTimestamp("updated_at").toInstant());

  public List<AgencyEnquiryResponse> enquiries(UUID org, int limit, int offset) {
    return jdbc.query(
        "SELECT c.id,c.property_id,c.listing_id,c.subject FROM conversations c WHERE"
            + " c.kind='ENQUIRY' AND c.channel='SHARED' AND agency_property_access(?,c.property_id)"
            + " ORDER BY c.updated_at DESC,c.id LIMIT ? OFFSET ?",
        (r, n) ->
            new AgencyEnquiryResponse(
                r.getObject("id", UUID.class),
                r.getObject("property_id", UUID.class),
                r.getObject("listing_id", UUID.class),
                r.getString("subject")),
        org,
        limit,
        offset);
  }

  public UUID leaseApplication(UUID lease) {
    return jdbc.queryForObject(
        "SELECT application_id FROM rental_leases WHERE id=?", UUID.class, lease);
  }

  public boolean assigned(UUID org, UUID property, UUID user) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM agency_property_assignments a JOIN"
                + " organization_memberships m ON m.organization_id=a.agency_id AND"
                + " m.user_id=a.agent_id WHERE a.agency_id=? AND a.property_id=? AND a.agent_id=?"
                + " AND a.active AND agency_property_access(a.agency_id,a.property_id))",
            Boolean.class,
            org,
            property,
            user));
  }

  public List<AgencyPropertyResponse> properties(UUID org, UUID actor, int limit, int offset) {
    var a = args(org, actor);
    a.put("limit", limit);
    a.put("offset", offset);
    return named.query(
        "SELECT p.id,p.name,p.city,p.country_code,p.status,p.version FROM properties p WHERE"
            + " agency_property_access(:org,p.id) AND ("
            + MANAGER
            + " OR EXISTS(SELECT 1 FROM agency_property_assignments a WHERE a.agency_id=:org AND"
            + " a.property_id=p.id AND a.agent_id=:actor AND a.active)) ORDER BY p.name,p.id LIMIT"
            + " :limit OFFSET :offset",
        a,
        (r, n) ->
            new AgencyPropertyResponse(
                r.getObject("id", UUID.class),
                r.getString("name"),
                r.getString("city"),
                r.getString("country_code"),
                r.getString("status"),r.getLong("version")));
  }

  public List<AgencyAgentResponse> agents(UUID org, int limit, int offset) {
    return jdbc.query(
        "SELECT u.id,u.display_name,m.role FROM organization_memberships m JOIN app_users u ON"
            + " u.id=m.user_id WHERE m.organization_id=? AND u.active ORDER BY u.display_name,u.id"
            + " LIMIT ? OFFSET ?",
        (r, n) ->
            new AgencyAgentResponse(
                r.getObject("id", UUID.class), r.getString("display_name"), r.getString("role")),
        org,
        limit,
        offset);
  }

  public List<AgencyPropertyAssignment> assignments(UUID org, UUID actor, int limit, int offset) {
    var a = args(org, actor);
    a.put("limit", limit);
    a.put("offset", offset);
    return named.query(
        "SELECT * FROM agency_property_assignments a WHERE a.agency_id=:org AND ("
            + MANAGER
            + " OR (a.agent_id=:actor AND a.active AND agency_property_access(:org,a.property_id)))"
            + " ORDER BY a.updated_at DESC,a.id LIMIT :limit OFFSET :offset",
        a,
        assignmentMapper);
  }

  public Optional<AgencyPropertyAssignment> assignment(UUID org, UUID property, UUID user) {
    return jdbc
        .query(
            "SELECT * FROM agency_property_assignments WHERE agency_id=? AND property_id=? AND"
                + " agent_id=?",
            assignmentMapper,
            org,
            property,
            user)
        .stream()
        .findFirst();
  }

  public AgencyPropertyAssignment assign(
      UUID org, UUID property, UUID user, boolean active, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " agency_property_assignments(id,agency_id,property_id,agent_id,active,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?) ON CONFLICT(agency_id,property_id,agent_id) DO UPDATE SET"
            + " active=EXCLUDED.active,version=agency_property_assignments.version+1,updated_at=EXCLUDED.updated_at",
        UUID.randomUUID(),
        org,
        property,
        user,
        active,
        time(now),
        time(now));
    return assignment(org, property, user).orElseThrow();
  }

  public Optional<AgencyClient> client(UUID org, UUID actor, UUID id, boolean lock) {
    var a = args(org, actor);
    a.put("id", id);
    return named
        .query(
            "SELECT c.* FROM agency_clients c WHERE c.agency_id=:org AND c.id=:id AND ("
                + MANAGER
                + " OR EXISTS(SELECT 1 FROM agency_leads l WHERE l.agency_id=c.agency_id AND"
                + " l.client_id=c.id AND "
                + LEAD_VISIBLE
                + "))"
                + (lock ? " FOR UPDATE OF c" : ""),
            a,
            clientMapper())
        .stream()
        .findFirst();
  }

  public List<AgencyClient> clients(UUID org, UUID actor, String q, int limit, int offset) {
    var a = args(org, actor);
    a.put("q", q == null ? "" : q.toLowerCase(Locale.ROOT));
    a.put("limit", limit);
    a.put("offset", offset);
    return named.query(
        "SELECT c.* FROM agency_clients c WHERE c.agency_id=:org AND ("
            + MANAGER
            + " OR EXISTS(SELECT 1 FROM agency_leads l WHERE l.agency_id=c.agency_id AND"
            + " l.client_id=c.id AND "
            + LEAD_VISIBLE
            + ")) AND (:q='' OR position(:q IN lower(c.details->>'fullName'))>0) ORDER BY"
            + " c.updated_at DESC,c.id LIMIT :limit OFFSET :offset",
        a,
        clientMapper());
  }

  public UUID createClient(
      UUID org, UUID actor, UUID customer, AgencyClientDetails d, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " agency_clients(id,agency_id,customer_user_id,details,status,created_by,created_at,updated_at)"
            + " VALUES (?,?,?,?::jsonb,'ACTIVE',?,?,?)",
        id,
        org,
        customer,
        json.writeValueAsString(d),
        actor,
        time(now),
        time(now));
    return id;
  }

  public void clientDetails(UUID id, AgencyClientDetails d, Instant now) {
    jdbc.update(
        "UPDATE agency_clients SET details=?::jsonb,version=version+1,updated_at=? WHERE id=?",
        json.writeValueAsString(d),
        time(now),
        id);
  }

  public void clientStatus(UUID id, String status, Instant now) {
    jdbc.update(
        "UPDATE agency_clients SET status=?,version=version+1,updated_at=? WHERE id=?",
        status,
        time(now),
        id);
  }

  public void bindCustomer(UUID id, UUID customer, Instant now) {
    jdbc.update(
        "UPDATE agency_clients SET customer_user_id=?,version=version+1,updated_at=? WHERE id=?",
        customer,
        time(now),
        id);
  }

  public Optional<AgencyLead> lead(UUID org, UUID actor, UUID id, boolean lock) {
    var a = args(org, actor);
    a.put("id", id);
    return named
        .query(
            "SELECT l.* FROM agency_leads l WHERE l.agency_id=:org AND l.id=:id AND "
                + LEAD_VISIBLE
                + (lock ? " FOR UPDATE OF l" : ""),
            a,
            leadMapper)
        .stream()
        .findFirst();
  }

  public List<AgencyLead> leads(
      UUID org, UUID actor, String stage, boolean mine, String q, int limit, int offset) {
    var a = args(org, actor);
    a.put("stage", stage);
    a.put("mine", mine);
    a.put("q", q == null ? "" : q.toLowerCase(Locale.ROOT));
    a.put("limit", limit);
    a.put("offset", offset);
    return named.query(
        "SELECT l.* FROM agency_leads l WHERE l.agency_id=:org AND "
            + LEAD_VISIBLE
            + " AND (CAST(:stage AS text) IS NULL OR l.stage=:stage) AND (NOT :mine OR"
            + " l.assigned_to=:actor) AND (:q='' OR position(:q IN lower(l.title))>0) ORDER BY"
            + " l.updated_at DESC,l.id LIMIT :limit OFFSET :offset",
        a,
        leadMapper);
  }

  public UUID createLead(UUID org, UUID actor, AgencyLeadCreateRequest r, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " agency_leads(id,agency_id,client_id,title,stage,assigned_to,property_id,listing_id,enquiry_id,created_by,created_at,updated_at)"
            + " VALUES (?,?,?,?,'NEW',?,?,?,?,?,?,?)",
        id,
        org,
        r.clientId(),
        r.title().strip(),
        r.agentId(),
        r.propertyId(),
        r.listingId(),
        r.enquiryId(),
        actor,
        time(now),
        time(now));
    return id;
  }

  public void leadAssignment(UUID id, UUID agent, Instant now) {
    jdbc.update(
        "UPDATE agency_leads SET assigned_to=?,version=version+1,updated_at=? WHERE id=?",
        agent,
        time(now),
        id);
  }

  public void leadLinks(UUID id, UUID property, UUID listing, UUID enquiry, Instant now) {
    jdbc.update(
        "UPDATE agency_leads SET"
            + " property_id=?,listing_id=?,enquiry_id=?,viewing_id=NULL,application_id=NULL,lease_id=NULL,version=version+1,updated_at=?"
            + " WHERE id=?",
        property,
        listing,
        enquiry,
        time(now),
        id);
  }

  public void leadStage(
      UUID id, String stage, UUID viewing, UUID application, UUID lease, Instant now) {
    jdbc.update(
        "UPDATE agency_leads SET"
            + " stage=?,viewing_id=COALESCE(?,viewing_id),application_id=COALESCE(?,application_id),lease_id=COALESCE(?,lease_id),won_at=CASE"
            + " WHEN ?='WON' THEN ? ELSE won_at END,version=version+1,updated_at=? WHERE id=?",
        stage,
        viewing,
        application,
        lease,
        stage,
        time(now),
        time(now),
        id);
  }

  public List<AgencyWorkflowChoice> workflow(AgencyLead l, UUID customer) {
    var result = new ArrayList<AgencyWorkflowChoice>();
    if (l.enquiryId() != null)
      result.addAll(
          jdbc.query(
              "SELECT id,status FROM viewing_appointments WHERE conversation_id=? ORDER BY"
                  + " created_at DESC LIMIT 100",
              (r, n) ->
                  new AgencyWorkflowChoice(
                      r.getObject("id", UUID.class), "VIEWING", r.getString("status")),
              l.enquiryId()));
    if (l.propertyId() != null && customer != null) {
      result.addAll(
          jdbc.query(
              "SELECT id,status FROM rental_applications WHERE property_id=? AND applicant_id=? AND"
                  + " submitted_at IS NOT NULL ORDER BY created_at DESC LIMIT 100",
              (r, n) ->
                  new AgencyWorkflowChoice(
                      r.getObject("id", UUID.class), "APPLICATION", r.getString("status")),
              l.propertyId(),
              customer));
      result.addAll(
          jdbc.query(
              "SELECT id,status FROM rental_leases WHERE property_id=? AND tenant_id=? AND"
                  + " first_shared_at IS NOT NULL ORDER BY created_at DESC LIMIT 100",
              (r, n) ->
                  new AgencyWorkflowChoice(
                      r.getObject("id", UUID.class), "LEASE", r.getString("status")),
              l.propertyId(),
              customer));
    }
    return List.copyOf(result);
  }

  public Optional<AgencyTask> task(UUID org, UUID actor, UUID id, boolean lock) {
    var a = args(org, actor);
    a.put("id", id);
    return named
        .query(
            "SELECT t.* FROM agency_tasks t JOIN agency_leads l ON l.id=t.lead_id AND"
                + " l.agency_id=t.agency_id WHERE t.agency_id=:org AND t.id=:id AND "
                + LEAD_VISIBLE
                + " AND ("
                + MANAGER
                + " OR t.assignee_id=:actor)"
                + (lock ? " FOR UPDATE OF t" : ""),
            a,
            taskMapper)
        .stream()
        .findFirst();
  }

  public List<AgencyTask> tasks(
      UUID org,
      UUID actor,
      UUID lead,
      String status,
      String due,
      Instant now,
      int limit,
      int offset) {
    var a = args(org, actor);
    a.put("lead", lead);
    a.put("status", status);
    a.put("due", due);
    a.put("now", time(now));
    a.put("limit", limit);
    a.put("offset", offset);
    return named.query(
        "SELECT t.* FROM agency_tasks t JOIN agency_leads l ON l.id=t.lead_id AND"
            + " l.agency_id=t.agency_id WHERE t.agency_id=:org AND "
            + LEAD_VISIBLE
            + " AND ("
            + MANAGER
            + " OR t.assignee_id=:actor) AND (CAST(:lead AS uuid) IS NULL OR t.lead_id=:lead) AND"
            + " (CAST(:status AS text) IS NULL OR t.status=:status) AND (CAST(:due AS text) IS NULL"
            + " OR (:due='OVERDUE' AND t.status='OPEN' AND t.due_at<:now) OR (:due='UPCOMING' AND"
            + " t.status='OPEN' AND t.due_at>=:now)) ORDER BY t.due_at,t.id LIMIT :limit OFFSET"
            + " :offset",
        a,
        taskMapper);
  }

  public UUID createTask(UUID org, AgencyTaskRequest r, Instant now) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " agency_tasks(id,agency_id,lead_id,assignee_id,title,kind,due_at,description,status,created_at,updated_at)"
            + " VALUES (?,?,?,?,?,?,?,?,'OPEN',?,?)",
        id,
        org,
        r.leadId(),
        r.assigneeId(),
        r.title().strip(),
        r.kind(),
        time(r.dueAt()),
        r.description().strip(),
        time(now),
        time(now));
    return id;
  }

  public void taskState(UUID id, String status, Instant due, Instant now) {
    jdbc.update(
        "UPDATE agency_tasks SET status=?,due_at=COALESCE(?,due_at),version=version+1,updated_at=?"
            + " WHERE id=?",
        status,
        time(due),
        time(now),
        id);
  }

  public Optional<Map<String, Object>> operation(UUID org, UUID request) {
    return jdbc
        .queryForList(
            "SELECT * FROM agency_operations WHERE agency_id=? AND request_id=?", org, request)
        .stream()
        .findFirst();
  }

  public void operation(
      UUID org,
      UUID actor,
      UUID request,
      String action,
      UUID target,
      String fingerprint,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " agency_operations(agency_id,request_id,actor_id,action,target_id,fingerprint,created_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        org,
        request,
        actor,
        action,
        target,
        fingerprint,
        time(now));
  }

  public void event(
      UUID org, UUID actor, String kind, UUID target, String action, String message, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " agency_events(id,agency_id,target_kind,target_id,actor_id,action,message,created_at)"
            + " VALUES (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(),
        org,
        kind,
        target,
        actor,
        action,
        message,
        time(now));
  }

  public List<AgencyEvent> history(UUID org, String kind, UUID target, int limit, int offset) {
    return jdbc.query(
        "SELECT * FROM agency_events WHERE agency_id=? AND target_kind=? AND target_id=? ORDER BY"
            + " created_at DESC,id LIMIT ? OFFSET ?",
        (r, n) ->
            new AgencyEvent(
                r.getObject("id", UUID.class),
                r.getString("target_kind"),
                r.getObject("target_id", UUID.class),
                r.getObject("actor_id", UUID.class),
                r.getString("action"),
                r.getString("message"),
                r.getTimestamp("created_at").toInstant()),
        org,
        kind,
        target,
        limit,
        offset);
  }

  public AgencyDashboardResponse dashboard(UUID org, UUID actor, boolean manager, Instant now) {
    var a = args(org, actor);
    a.put("now", time(now));
    a.put(
        "tomorrow",
        time(
            now.atOffset(java.time.ZoneOffset.UTC)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay()
                .toInstant(java.time.ZoneOffset.UTC)));
    var stages = new LinkedHashMap<String, Long>();
    named.query(
        "SELECT l.stage,count(*) total FROM agency_leads l WHERE l.agency_id=:org AND "
            + LEAD_VISIBLE
            + " GROUP BY l.stage",
        a,
        r -> {
          stages.put(r.getString("stage"), r.getLong("total"));
        });
    long properties =
        named.queryForObject(
            "SELECT count(*) FROM properties p WHERE agency_property_access(:org,p.id) AND ("
                + MANAGER
                + " OR EXISTS(SELECT 1 FROM agency_property_assignments x WHERE x.agency_id=:org"
                + " AND x.property_id=p.id AND x.agent_id=:actor AND x.active))",
            a,
            Long.class);
    long clients =
        named.queryForObject(
            "SELECT count(*) FROM agency_clients c WHERE c.agency_id=:org AND ("
                + MANAGER
                + " OR EXISTS(SELECT 1 FROM agency_leads l WHERE l.agency_id=c.agency_id AND"
                + " l.client_id=c.id AND "
                + LEAD_VISIBLE
                + "))",
            a,
            Long.class);
    String tasks =
        " FROM agency_tasks t JOIN agency_leads l ON l.id=t.lead_id WHERE t.agency_id=:org AND "
            + LEAD_VISIBLE
            + " AND ("
            + MANAGER
            + " OR t.assignee_id=:actor) AND t.status='OPEN'";
    long overdue =
        named.queryForObject("SELECT count(*)" + tasks + " AND t.due_at<:now", a, Long.class);
    long today =
        named.queryForObject(
            "SELECT count(*)" + tasks + " AND t.due_at>=:now AND t.due_at<:tomorrow",
            a,
            Long.class);
    long unassigned =
        named.queryForObject(
            "SELECT count(*) FROM agency_leads l WHERE l.agency_id=:org AND l.assigned_to IS NULL"
                + " AND "
                + LEAD_VISIBLE,
            a,
            Long.class);
    return new AgencyDashboardResponse(
        manager, properties, clients, Map.copyOf(stages), today, overdue, unassigned);
  }
}
