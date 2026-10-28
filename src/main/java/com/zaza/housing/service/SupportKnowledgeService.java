package com.zaza.housing.service;

import com.zaza.housing.dto.*;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.repository.SupportKnowledgeRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SupportKnowledgeService {
  private final SupportKnowledgeRepository repository;
  private final AdministrationAccessService access;
  private final AdministrationActivityService activity;
  private final Clock clock;

  public SupportKnowledgeService(
      SupportKnowledgeRepository repository,
      AdministrationAccessService access,
      AdministrationActivityService activity,
      Clock clock) {
    this.repository = repository;
    this.access = access;
    this.activity = activity;
    this.clock = clock;
  }

  public List<Map<String, Object>> publicTopics(String q, int l, int f) {
    Pagination.validate(l, f);
    if (q != null && q.length() > 150) throw AgencyAccessService.bad("Search is too long.");
    return repository.list(false, q == null ? "" : q.strip(), l, f);
  }

  public List<Map<String, Object>> list(AuthenticatedUser a, String token, String q, int l, int f) {
    access.admin(a, token, false);
    Pagination.validate(l, f);
    return repository.list(true, q == null ? "" : q.strip(), l, f);
  }

  public Map<String, Object> get(UUID id, boolean admin) {
    var value = repository.get(id, false);
    if (value == null || (!admin && !"PUBLISHED".equals(value.get("status"))))
      throw CaretakerAccessService.missing();
    return value;
  }

  @Transactional
  public Map<String, Object> save(
      AuthenticatedUser a, String token, UUID id, SupportKnowledgeRequest r) {
    access.admin(a, token, true);
    access.password(a, r.password());
    String action = id == null ? "SUPPORT_TOPIC_CREATED" : "SUPPORT_TOPIC_REVISED";
    UUID replay = activity.replay(a, r.requestId(), action, id, r);
    if (replay != null) return get(replay, true);
    if (id == null) {
      if (r.version() != 0) throw AgencyAccessService.conflict("New topics start at version zero.");
      id = repository.create(a.id(), r, clock.instant());
    } else {
      var value = repository.get(id, true);
      if (value == null) throw CaretakerAccessService.missing();
      AdministrationAccessService.version(value.get("version"), r.version());
      if (!r.slug().equals(value.get("slug")))
        throw AgencyAccessService.bad("A topic's permanent slug cannot change.");
      repository.update(id, a.id(), r, clock.instant());
    }
    activity.record(
        a,
        r.requestId(),
        action,
        "KNOWLEDGE",
        id,
        r,
        "Help topic saved as a private draft.",
        false);
    return get(id, true);
  }

  @Transactional
  public Map<String, Object> command(
      AuthenticatedUser a, String token, UUID id, String command, AdministrationCommandRequest r) {
    access.admin(a, token, true);
    access.password(a, r.password());
    var value = repository.get(id, true);
    if (value == null) throw CaretakerAccessService.missing();
    String action = "SUPPORT_TOPIC_" + command.toUpperCase(Locale.ROOT);
    if (activity.replay(a, r.requestId(), action, id, r) != null) return get(id, true);
    AdministrationAccessService.version(value.get("version"), r.version());
    if (!Set.of("publish", "archive").contains(command))
      throw AgencyAccessService.bad("Unknown topic action.");
    repository.state(
        id, a.id(), command.equals("publish") ? "PUBLISHED" : "ARCHIVED", clock.instant());
    activity.record(a, r.requestId(), action, "KNOWLEDGE", id, r, r.reason().strip(), false);
    return get(id, true);
  }
}
