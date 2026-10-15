package com.zaza.housing.service;

import com.zaza.housing.dto.ConversationReportEvidenceResponse;
import com.zaza.housing.dto.ConversationReportQueueResponse;
import com.zaza.housing.dto.ConversationReportResponse;
import com.zaza.housing.dto.ReportConversationMessageRequest;
import com.zaza.housing.dto.ResolveConversationReportRequest;
import com.zaza.housing.dto.ReviewConversationReportRequest;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.ConversationReport;
import com.zaza.housing.repository.ConversationModerationRepository;
import com.zaza.housing.repository.ConversationRepository;
import com.zaza.housing.util.Pagination;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConversationModerationService {
  private final ConversationModerationRepository reports;
  private final ConversationRepository conversations;
  private final ConversationAccessService access;
  private final ConversationActivityService activity;
  private final AuthService auth;
  private final AuditService audit;
  private final Clock clock;

  public ConversationModerationService(
      ConversationModerationRepository reports,
      ConversationRepository conversations,
      ConversationAccessService access,
      ConversationActivityService activity,
      AuthService auth,
      AuditService audit,
      Clock clock) {
    this.reports = reports;
    this.conversations = conversations;
    this.access = access;
    this.activity = activity;
    this.auth = auth;
    this.audit = audit;
    this.clock = clock;
  }

  private ConversationReport find(UUID id) {
    return reports
        .find(id)
        .orElseThrow(() -> WorkAccessService.missing("Reported message case not found."));
  }

  private ConversationReportResponse response(ConversationReport r) {
    return new ConversationReportResponse(
        r.id(),
        r.conversationId(),
        r.messageId(),
        r.category(),
        r.reason(),
        r.status(),
        r.version(),
        r.resolution(),
        r.resolutionReason(),
        r.createdAt());
  }

  private void independent(AuthenticatedUser actor, ConversationReport r) {
    if (conversations.access(actor.id(), r.conversationId()).isPresent()
        || conversations.participated(r.conversationId(), actor.id())
        || actor.id().equals(r.reporterId()))
      throw WorkAccessService.forbidden(
          "An operator independent of the conversation parties must review this case.");
  }

  private void version(ConversationReport r, long expected) {
    if (r.version() != expected)
      throw WorkAccessService.conflict(
          "The reported message case changed. Reload before continuing.");
  }

  private void assigned(AuthenticatedUser actor, ConversationReport r) {
    access.operator(actor);
    if (!actor.id().equals(r.assignedTo()))
      throw WorkAccessService.missing("Assigned moderation case not found.");
  }

  public ConversationReportResponse own(AuthenticatedUser actor, UUID id) {
    var r = find(id);
    if (!actor.id().equals(r.reporterId()))
      throw WorkAccessService.missing("Reported message case not found.");
    return response(r);
  }

  public List<ConversationReportResponse> own(AuthenticatedUser actor, int limit, int offset) {
    Pagination.validate(limit, offset);
    return reports.own(actor.id(), limit, offset).stream().map(this::response).toList();
  }

  public List<ConversationReportQueueResponse> queue(
      AuthenticatedUser actor, String status, boolean mine, int limit, int offset) {
    access.operator(actor);
    Pagination.validate(limit, offset);
    if (status != null && !Set.of("OPEN", "ASSIGNED", "RESOLVED").contains(status))
      throw WorkAccessService.invalid("Unknown moderation case status.");
    return reports.queue(actor.id(), status, mine, limit, offset);
  }

  @Transactional
  public ConversationReportResponse report(
      AuthenticatedUser actor,
      UUID conversation,
      UUID message,
      ReportConversationMessageRequest r) {
    var a = access.get(actor, conversation, true);
    var body = Map.of("messageId", message, "request", r);
    var replay = activity.replay(actor, conversation, r.requestId(), "MESSAGE_REPORTED", body);
    if (replay.isPresent()) return own(actor, replay.get());
    var m =
        conversations
            .message(conversation, message)
            .orElseThrow(() -> WorkAccessService.missing("Message not found."));
    if (actor.id().equals(m.get("author_id")))
      throw WorkAccessService.invalid(
          "Withdraw your own message rather than reporting it as another party's message.");
    UUID id = reports.create(conversation, message, actor.id(), r, clock.instant());
    activity.operation(actor, conversation, r.requestId(), "MESSAGE_REPORTED", body, id);
    audit.record(actor.id(), a.organizationId(), "CONVERSATION_MESSAGE_REPORTED", id);
    return own(actor, id);
  }

  @Transactional
  public ConversationReportEvidenceResponse evidence(AuthenticatedUser actor, UUID id) {
    var r = find(id);
    assigned(actor, r);
    independent(actor, r);
    audit.record(actor.id(), null, "CONVERSATION_REPORT_EVIDENCE_READ", id);
    return new ConversationReportEvidenceResponse(
        response(r), conversations.evidence(r.conversationId(), r.messageId()));
  }

  @Transactional
  public ConversationReportResponse claim(
      AuthenticatedUser actor, UUID id, ReviewConversationReportRequest r) {
    access.operator(actor);
    var report = find(id);
    var c = access.moderationLock(report.conversationId());
    report = find(id);
    if (conversations.access(actor.id(), c.id()).isPresent()
        || conversations.participated(c.id(), actor.id())
        || actor.id().equals(report.reporterId()))
      throw WorkAccessService.forbidden(
          "An operator independent of the conversation parties must review this case.");
    auth.reauthenticate(actor, r.password());
    if (activity.replay(actor, id, r.requestId(), "REPORT_ASSIGNED", r).isPresent()) {
      assigned(actor, report);
      return response(report);
    }
    version(report, r.version());
    if (!report.status().equals("OPEN"))
      throw WorkAccessService.conflict("This case is already assigned or resolved.");
    reports.assign(id, actor.id());
    reports.decision(
        id, actor.id(), "ASSIGNED", ConversationService.text(r.reason()), clock.instant());
    activity.operation(actor, id, r.requestId(), "REPORT_ASSIGNED", r, id);
    audit.record(actor.id(), null, "CONVERSATION_REPORT_ASSIGNED", id);
    activity.reportEvent(actor, c, id, "REPORT_ASSIGNED", List.of(report.reporterId()));
    return response(find(id));
  }

  @Transactional
  public ConversationReportResponse release(
      AuthenticatedUser actor, UUID id, ReviewConversationReportRequest r) {
    access.operator(actor);
    var report = find(id);
    var c = access.moderationLock(report.conversationId());
    report = find(id);
    auth.reauthenticate(actor, r.password());
    if (activity.replay(actor, id, r.requestId(), "REPORT_RELEASED", r).isPresent())
      return response(report);
    assigned(actor, report);
    version(report, r.version());
    if (!report.status().equals("ASSIGNED"))
      throw WorkAccessService.conflict("Only an unresolved assigned case can be released.");
    reports.release(id);
    reports.decision(
        id, actor.id(), "RELEASED", ConversationService.text(r.reason()), clock.instant());
    activity.operation(actor, id, r.requestId(), "REPORT_RELEASED", r, id);
    audit.record(actor.id(), null, "CONVERSATION_REPORT_RELEASED", id);
    activity.reportEvent(actor, c, id, "REPORT_RELEASED", List.of(report.reporterId()));
    return response(find(id));
  }

  @Transactional
  public ConversationReportResponse resolve(
      AuthenticatedUser actor, UUID id, ResolveConversationReportRequest r) {
    access.operator(actor);
    var report = find(id);
    var c = access.moderationLock(report.conversationId());
    report = find(id);
    assigned(actor, report);
    independent(actor, report);
    auth.reauthenticate(actor, r.password());
    if (activity.replay(actor, id, r.requestId(), "REPORT_RESOLVED", r).isPresent())
      return response(report);
    version(report, r.version());
    if (!report.status().equals("ASSIGNED"))
      throw WorkAccessService.conflict("Only an unresolved assigned case can be decided.");
    if (r.resolution().equals("REDACT")) {
      conversations.redact(
          report.messageId(), actor.id(), ConversationService.text(r.reason()), clock.instant());
      activity.moderated(actor, c, report.messageId());
    }
    reports.resolve(id, r.resolution(), ConversationService.text(r.reason()));
    reports.decision(id, actor.id(), r.resolution(), r.reason(), clock.instant());
    activity.operation(actor, id, r.requestId(), "REPORT_RESOLVED", r, id);
    audit.record(actor.id(), null, "CONVERSATION_REPORT_RESOLVED", id);
    activity.reportEvent(actor, c, id, "REPORT_RESOLVED", List.of(report.reporterId()));
    return response(find(id));
  }
}
