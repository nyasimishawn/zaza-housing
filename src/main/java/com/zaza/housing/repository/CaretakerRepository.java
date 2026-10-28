package com.zaza.housing.repository;

import com.zaza.housing.dto.CaretakerDashboardResponse;
import com.zaza.housing.dto.CaretakerInvitationRequest;
import com.zaza.housing.dto.CaretakerMemberResponse;
import com.zaza.housing.dto.CaretakerPropertyResponse;
import com.zaza.housing.dto.CaretakerUnitResponse;
import com.zaza.housing.dto.PropertyTaskCreateRequest;
import com.zaza.housing.dto.PropertyTaskEvidenceResponse;
import com.zaza.housing.enums.CaretakerCapability;
import com.zaza.housing.enums.PropertyTaskKind;
import com.zaza.housing.enums.WorkPriority;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.CaretakerEvent;
import com.zaza.housing.model.CaretakerGrant;
import com.zaza.housing.model.ProcessedImage;
import com.zaza.housing.model.Property;
import com.zaza.housing.model.PropertyTask;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
public class CaretakerRepository {
  private final JdbcTemplate jdbc;
  private final NamedParameterJdbcTemplate named;
  private final ObjectMapper json;

  public CaretakerRepository(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.named = new NamedParameterJdbcTemplate(jdbc);
    this.json = json;
  }

  private static Timestamp time(Instant t) {
    return t == null ? null : Timestamp.from(t);
  }

  private static Instant instant(ResultSet r, String k) throws SQLException {
    return r.getTimestamp(k) == null ? null : r.getTimestamp(k).toInstant();
  }

  private Map<String, Object> args(UUID o, UUID a) {
    var m = new HashMap<String, Object>();
    m.put("org", o);
    m.put("actor", a);
    return m;
  }

  private static final String MANAGER =
      "EXISTS(SELECT 1 FROM organization_memberships m WHERE m.organization_id=:org AND"
          + " m.user_id=:actor AND m.role IN('OWNER','ADMIN'))";
  private static final String TASK_VISIBLE =
      "caretaker_property_access(:org,t.property_id,:actor,'OPERATIONS') AND ("
          + MANAGER
          + " OR (t.assignee_organization_id=:org AND t.assignee_id=:actor))";

  private RowMapper<CaretakerGrant> grantMapper() {
    return (r, n) ->
        new CaretakerGrant(
            r.getObject("id", UUID.class),
            r.getObject("organization_id", UUID.class),
            r.getObject("property_id", UUID.class),
            r.getObject("user_id", UUID.class),
            r.getString("user_name"),
            json.readValue(r.getString("property_snapshot"), Map.class),
            Set.copyOf(
                Arrays.asList(
                    json.readValue(r.getString("capabilities"), CaretakerCapability[].class))),
            r.getString("responsibilities"),
            r.getLong("property_version"),
            r.getString("status"),
            r.getBoolean("effective"),
            r.getLong("version"),
            instant(r, "expires_at"),
            r.getObject("invited_by", UUID.class),
            instant(r, "accepted_at"),
            instant(r, "closed_at"),
            instant(r, "created_at"),
            instant(r, "updated_at"));
  }

  private static final String GRANT_SELECT =
      "SELECT g.*,(g.status='ACTIVE' AND g.expires_at>statement_timestamp() AND"
          + " caretaker_property_access(g.organization_id,g.property_id,g.user_id,'OPERATIONS')) AS"
          + " effective FROM caretaker_assignments g ";
  private final RowMapper<PropertyTask> taskMapper =
      (r, n) ->
          new PropertyTask(
              r.getObject("id", UUID.class),
              r.getObject("owner_organization_id", UUID.class),
              r.getObject("property_id", UUID.class),
              r.getObject("unit_id", UUID.class),
              r.getString("property_name"),
              r.getString("location_label"),
              r.getObject("assignee_organization_id", UUID.class),
              r.getObject("assignee_id", UUID.class),
              r.getString("assignee_name"),
              PropertyTaskKind.valueOf(r.getString("kind")),
              WorkPriority.valueOf(r.getString("priority")),
              r.getString("title"),
              r.getString("description"),
              instant(r, "due_at"),
              r.getString("status"),
              r.getLong("version"),
              r.getString("completion_summary"),
              instant(r, "submitted_at"),
              instant(r, "completed_at"),
              r.getObject("completed_by", UUID.class),
              r.getObject("created_by", UUID.class),
              r.getObject("created_by_organization_id", UUID.class),
              instant(r, "created_at"),
              instant(r, "updated_at"));

  public boolean activeMember(UUID org, UUID user) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM organization_memberships m JOIN app_users u ON"
                + " u.id=m.user_id AND u.active WHERE m.organization_id=? AND m.user_id=?)",
            Boolean.class,
            org,
            user));
  }

  public boolean allowed(UUID o, UUID p, UUID a, String c) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT caretaker_property_access(?,?,?,?)", Boolean.class, o, p, a, c));
  }

  public List<CaretakerPropertyResponse> properties(UUID o, UUID a, int l, int f) {
    return jdbc.query(
        "SELECT p.* FROM properties p WHERE caretaker_property_access(?,p.id,?,'OPERATIONS') ORDER"
            + " BY p.name,p.id LIMIT ? OFFSET ?",
        (r, n) ->
            new CaretakerPropertyResponse(
                r.getObject("id", UUID.class),
                r.getString("name"),
                r.getString("address"),
                r.getString("city"),
                r.getString("country_code"),
                r.getString("status"),
                r.getLong("version"),
                capabilities(o, r.getObject("id", UUID.class), a)),
        o,
        a,
        l,
        f);
  }

  public Set<CaretakerCapability> capabilities(UUID o, UUID p, UUID a) {
    var set = EnumSet.noneOf(CaretakerCapability.class);
    for (var c : CaretakerCapability.values()) if (allowed(o, p, a, c.name())) set.add(c);
    return set;
  }

  public List<CaretakerMemberResponse> members(UUID o, UUID p, boolean eligible, int l, int f) {
    return jdbc.query(
        "SELECT m.user_id,u.display_name,m.role FROM organization_memberships m JOIN app_users u ON"
            + " u.id=m.user_id AND u.active WHERE m.organization_id=? AND (?=true OR"
            + " m.role='MEMBER') AND (?=false OR"
            + " caretaker_property_access(?,?,m.user_id,'OPERATIONS')) ORDER BY"
            + " u.display_name,m.user_id LIMIT ? OFFSET ?",
        (r, n) ->
            new CaretakerMemberResponse(
                r.getObject("user_id", UUID.class),
                r.getString("display_name"),
                r.getString("role")),
        o,
        eligible,
        eligible,
        o,
        p,
        l,
        f);
  }

  public List<CaretakerUnitResponse> units(UUID p, int l, int f) {
    return jdbc.query(
        "SELECT id,label,status,availability FROM property_units WHERE property_id=? AND"
            + " status='ACTIVE' ORDER BY label,id LIMIT ? OFFSET ?",
        (r, n) ->
            new CaretakerUnitResponse(
                r.getObject("id", UUID.class),
                r.getString("label"),
                r.getString("status"),
                r.getString("availability")),
        p,
        l,
        f);
  }

  public Optional<CaretakerUnitResponse> unit(UUID p, UUID id) {
    return jdbc
        .query(
            "SELECT id,label,status,availability FROM property_units WHERE property_id=? AND id=?"
                + " AND status='ACTIVE'",
            (r, n) ->
                new CaretakerUnitResponse(
                    r.getObject("id", UUID.class),
                    r.getString("label"),
                    r.getString("status"),
                    r.getString("availability")),
            p,
            id)
        .stream()
        .findFirst();
  }

  public Optional<CaretakerGrant> grant(UUID id, boolean lock) {
    return jdbc
        .query(GRANT_SELECT + "WHERE g.id=?" + (lock ? " FOR UPDATE OF g" : ""), grantMapper(), id)
        .stream()
        .findFirst();
  }

  public List<CaretakerGrant> grants(UUID o, UUID a, UUID p, int l, int f) {
    var x = args(o, a);
    x.put("property", p);
    x.put("limit", l);
    x.put("offset", f);
    return named.query(
        GRANT_SELECT
            + "WHERE ((g.organization_id=:org AND (g.user_id=:actor OR "
            + MANAGER
            + ")) OR (g.property_id=CAST(:property AS uuid) AND "
            + MANAGER
            + " AND EXISTS(SELECT 1 FROM properties p WHERE p.id=g.property_id AND"
            + " p.owner_organization_id=:org))) AND (CAST(:property AS uuid) IS NULL OR"
            + " g.property_id=:property) ORDER BY g.created_at DESC,g.id LIMIT :limit OFFSET"
            + " :offset",
        x,
        grantMapper());
  }

  public List<UUID> expired(UUID o, UUID p, UUID u) {
    return jdbc.query(
        "SELECT id FROM caretaker_assignments WHERE organization_id=? AND property_id=? AND"
            + " user_id=? AND status IN('INVITED','ACTIVE') AND expires_at<=statement_timestamp()"
            + " FOR UPDATE",
        (r, n) -> r.getObject("id", UUID.class),
        o,
        p,
        u);
  }

  public boolean openGrant(UUID o, UUID p, UUID u) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM caretaker_assignments WHERE organization_id=? AND"
                + " property_id=? AND user_id=? AND status IN('INVITED','ACTIVE'))",
            Boolean.class,
            o,
            p,
            u));
  }

  public boolean future(Instant at) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "SELECT CAST(? AS timestamptz)>statement_timestamp()", Boolean.class, time(at)));
  }

  public void invite(
      UUID id,
      UUID o,
      AuthenticatedUser a,
      Property p,
      CaretakerMemberResponse member,
      CaretakerInvitationRequest r,
      Instant now) {
    var snapshot =
        Map.of(
            "id",
            p.id(),
            "name",
            p.name(),
            "address",
            p.address(),
            "city",
            p.city(),
            "countryCode",
            p.countryCode(),
            "version",
            p.version());
    jdbc.update(
        "INSERT INTO"
            + " caretaker_assignments(id,organization_id,property_id,user_id,user_name,property_snapshot,property_version,capabilities,responsibilities,status,expires_at,invited_by,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?::jsonb,?,?::jsonb,?,'INVITED',?,?,?,?)",
        id,
        o,
        p.id(),
        member.id(),
        member.displayName(),
        json.writeValueAsString(snapshot),
        p.version(),
        json.writeValueAsString(r.capabilities().stream().map(Enum::name).sorted().toList()),
        r.responsibilities().trim(),
        time(r.expiresAt()),
        a.id(),
        time(now),
        time(now));
  }

  public void grantStatus(UUID id, String status, Instant now) {
    jdbc.update(
        "UPDATE caretaker_assignments SET status=?,version=version+1,updated_at=?,accepted_at=CASE"
            + " WHEN ?='ACTIVE' THEN ? ELSE accepted_at END,closed_at=CASE WHEN ?"
            + " IN('DECLINED','REVOKED','EXPIRED') THEN CAST(? AS timestamptz) ELSE NULL END WHERE"
            + " id=?",
        status,
        time(now),
        status,
        time(now),
        status,
        time(now),
        id);
  }

  public List<PropertyTask> tasks(
      UUID o, UUID a, UUID p, String status, UUID assignee, int l, int f) {
    var x = args(o, a);
    x.put("property", p);
    x.put("status", status);
    x.put("assignee", assignee);
    x.put("limit", l);
    x.put("offset", f);
    return named.query(
        "SELECT t.* FROM property_operations_tasks t WHERE "
            + TASK_VISIBLE
            + " AND (CAST(:property AS uuid) IS NULL OR t.property_id=:property) AND (CAST(:status"
            + " AS text) IS NULL OR t.status=:status) AND (CAST(:assignee AS uuid) IS NULL OR"
            + " t.assignee_id=:assignee) ORDER BY t.due_at,t.id LIMIT :limit OFFSET :offset",
        x,
        taskMapper);
  }

  public Optional<PropertyTask> task(UUID o, UUID a, UUID id, boolean lock) {
    var x = args(o, a);
    x.put("id", id);
    return named
        .query(
            "SELECT t.* FROM property_operations_tasks t WHERE t.id=:id AND "
                + TASK_VISIBLE
                + (lock ? " FOR UPDATE OF t" : ""),
            x,
            taskMapper)
        .stream()
        .findFirst();
  }

  public void createTask(
      UUID id,
      UUID o,
      UUID actor,
      Property p,
      String location,
      CaretakerMemberResponse member,
      PropertyTaskCreateRequest r,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " property_operations_tasks(id,owner_organization_id,property_id,unit_id,property_name,location_label,assignee_organization_id,assignee_id,assignee_name,kind,priority,title,description,due_at,status,created_by,created_by_organization_id,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',?,?,?,?)",
        id,
        p.ownerOrganizationId(),
        p.id(),
        r.unitId(),
        p.name(),
        location,
        o,
        member.id(),
        member.displayName(),
        r.kind().name(),
        r.priority().name(),
        r.title().trim(),
        r.description().trim(),
        time(r.dueAt()),
        actor,
        o,
        time(now),
        time(now));
  }

  public void taskStatus(UUID id, String status, String summary, UUID completedBy, Instant now) {
    jdbc.update(
        "UPDATE property_operations_tasks SET"
            + " status=?,version=version+1,updated_at=?,completion_summary=CASE WHEN ?='SUBMITTED'"
            + " THEN ? ELSE completion_summary END,submitted_at=CASE WHEN ?='SUBMITTED' THEN ? ELSE"
            + " submitted_at END,completed_by=CASE WHEN ?='COMPLETED' THEN ? ELSE completed_by"
            + " END,completed_at=CASE WHEN ?='COMPLETED' THEN ? ELSE completed_at END WHERE id=?",
        status,
        time(now),
        status,
        summary,
        status,
        time(now),
        status,
        completedBy,
        status,
        time(now),
        id);
  }

  public void taskAssign(UUID id, UUID o, CaretakerMemberResponse member, Instant now) {
    jdbc.update(
        "UPDATE property_operations_tasks SET"
            + " assignee_organization_id=?,assignee_id=?,assignee_name=?,version=version+1,updated_at=?"
            + " WHERE id=?",
        o,
        member.id(),
        member.displayName(),
        time(now),
        id);
  }

  public void taskDue(UUID id, Instant due, Instant now) {
    jdbc.update(
        "UPDATE property_operations_tasks SET due_at=?,version=version+1,updated_at=? WHERE id=?",
        time(due),
        time(now),
        id);
  }

  public void taskTouch(UUID id, Instant now) {
    jdbc.update(
        "UPDATE property_operations_tasks SET version=version+1,updated_at=? WHERE id=?",
        time(now),
        id);
  }

  public CaretakerDashboardResponse dashboard(UUID o, UUID a, boolean manager) {
    var x = args(o, a);
    var counts = new LinkedHashMap<String, Long>();
    named.query(
        "SELECT t.status,count(*) AS total FROM property_operations_tasks t WHERE "
            + TASK_VISIBLE
            + " GROUP BY t.status",
        x,
        r -> {
          counts.put(r.getString("status"), r.getLong("total"));
        });
    long properties =
        jdbc.queryForObject(
            "SELECT count(*) FROM properties p WHERE"
                + " caretaker_property_access(?,p.id,?,'OPERATIONS')",
            Long.class,
            o,
            a);
    long invitations =
        jdbc.queryForObject(
            "SELECT count(*) FROM caretaker_assignments WHERE organization_id=? AND user_id=? AND"
                + " status='INVITED' AND expires_at>statement_timestamp()",
            Long.class,
            o,
            a);
    long overdue =
        named.queryForObject(
            "SELECT count(*) FROM property_operations_tasks t WHERE "
                + TASK_VISIBLE
                + " AND t.status NOT IN('COMPLETED','CANCELLED') AND"
                + " t.due_at<statement_timestamp()",
            x,
            Long.class);
    long today =
        named.queryForObject(
            "SELECT count(*) FROM property_operations_tasks t WHERE "
                + TASK_VISIBLE
                + " AND t.status NOT IN('COMPLETED','CANCELLED') AND"
                + " t.due_at>=date_trunc('day',statement_timestamp()) AND"
                + " t.due_at<date_trunc('day',statement_timestamp())+interval '1 day'",
            x,
            Long.class);
    return new CaretakerDashboardResponse(
        o, manager, properties, invitations, Map.copyOf(counts), overdue, today);
  }

  public Optional<Map<String, Object>> operation(UUID o, UUID request) {
    return jdbc
        .queryForList(
            "SELECT actor_id,action,target_id,fingerprint FROM caretaker_operations WHERE"
                + " organization_id=? AND request_id=?",
            o,
            request)
        .stream()
        .findFirst();
  }

  public void operation(
      UUID o, UUID a, UUID request, String action, UUID target, String hash, Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " caretaker_operations(organization_id,actor_id,request_id,action,target_id,fingerprint,created_at)"
            + " VALUES(?,?,?,?,?,?,?)",
        o,
        a,
        request,
        action,
        target,
        hash,
        time(now));
  }

  public UUID event(
      UUID o, UUID a, UUID p, String kind, UUID id, String action, String message, Instant now) {
    UUID e = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO"
            + " caretaker_events(id,property_id,target_kind,target_id,actor_id,organization_id,action,message,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        e,
        p,
        kind,
        id,
        a,
        o,
        action,
        message == null ? "" : message,
        time(now));
    return e;
  }

  public List<CaretakerEvent> history(String kind, UUID id, int l, int f) {
    return jdbc.query(
        "SELECT * FROM caretaker_events WHERE target_kind=? AND target_id=? ORDER BY created_at"
            + " DESC,id LIMIT ? OFFSET ?",
        (r, n) ->
            new CaretakerEvent(
                r.getObject("id", UUID.class),
                r.getString("target_kind"),
                r.getObject("target_id", UUID.class),
                r.getObject("actor_id", UUID.class),
                r.getObject("organization_id", UUID.class),
                r.getString("action"),
                r.getString("message"),
                instant(r, "created_at")),
        kind,
        id,
        l,
        f);
  }

  public void notify(UUID user, UUID actor, UUID org, UUID target, UUID event, String kind) {
    if (user.equals(actor)) return;
    jdbc.update(
        "INSERT INTO"
            + " in_app_notifications(id,user_id,type,message,caretaker_target_id,caretaker_event_id,caretaker_url)"
            + " VALUES(?,?,'CARETAKER_ACTIVITY','A property operations record has an"
            + " update.',?,?,?) ON CONFLICT DO NOTHING",
        UUID.randomUUID(),
        user,
        target,
        event,
        "/api/v1/organizations/"
            + org
            + "/caretaker/"
            + (kind.equals("GRANT")
                ? "assignments/"
                : kind.equals("NOTICE")
                    ? "properties/"
                        + jdbc.queryForObject(
                            "SELECT property_id FROM caretaker_events WHERE id=?",
                            UUID.class,
                            event)
                        + "/notices/"
                    : "tasks/")
            + target);
  }

  public void notifyManagers(UUID p, UUID actor, UUID target, UUID event, String kind) {
    jdbc.query(
        "SELECT DISTINCT ON(m.user_id) m.user_id,m.organization_id FROM organization_memberships m"
            + " JOIN properties p ON p.id=? AND agency_management_access(m.organization_id,p.id)"
            + " JOIN app_users u ON u.id=m.user_id AND u.active WHERE m.role IN('OWNER','ADMIN')"
            + " ORDER BY m.user_id,CASE WHEN m.organization_id=p.owner_organization_id THEN 0 ELSE"
            + " 1 END",
        r -> {
          notify(
              r.getObject("user_id", UUID.class),
              actor,
              r.getObject("organization_id", UUID.class),
              target,
              event,
              kind);
        },
        p);
  }

  public List<PropertyTaskEvidenceResponse> evidence(UUID task) {
    return jdbc.query(
        "SELECT * FROM property_task_evidence WHERE task_id=? ORDER BY created_at,id",
        (r, n) ->
            new PropertyTaskEvidenceResponse(
                r.getObject("id", UUID.class),
                r.getObject("task_id", UUID.class),
                r.getObject("author_id", UUID.class),
                r.getString("caption"),
                r.getString("content_type"),
                r.getInt("width"),
                r.getInt("height"),
                r.getLong("byte_size"),
                r.getString("sha256"),
                instant(r, "created_at")),
        task);
  }

  public Optional<String> evidenceKey(UUID task, UUID id) {
    return jdbc
        .query(
            "SELECT storage_key FROM property_task_evidence WHERE task_id=? AND id=?",
            (r, n) -> r.getString(1),
            task,
            id)
        .stream()
        .findFirst();
  }

  public void evidence(
      UUID id,
      UUID task,
      UUID actor,
      String caption,
      String key,
      ProcessedImage image,
      String hash,
      Instant now) {
    jdbc.update(
        "INSERT INTO"
            + " property_task_evidence(id,task_id,author_id,caption,storage_key,content_type,width,height,byte_size,sha256,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
        id,
        task,
        actor,
        caption,
        key,
        image.contentType(),
        image.width(),
        image.height(),
        image.bytes().length,
        hash,
        time(now));
  }
}
