package com.zaza.housing.service;

import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Conversation;
import com.zaza.housing.model.ConversationAccess;
import com.zaza.housing.repository.ConversationRepository;
import com.zaza.housing.util.RequestFingerprints;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class ConversationActivityService {
  private final ConversationRepository conversations;
  private final AuditService audit;
  private final NotificationService notifications;
  private final ObjectMapper json;
  private final Clock clock;

  public ConversationActivityService(
      ConversationRepository conversations,
      AuditService audit,
      NotificationService notifications,
      ObjectMapper json,
      Clock clock) {
    this.conversations = conversations;
    this.audit = audit;
    this.notifications = notifications;
    this.json = json;
    this.clock = clock;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<UUID> replay(
      AuthenticatedUser actor, UUID scope, UUID request, String action, Object body) {
    var found = conversations.operation(scope, request);
    if (found.isEmpty()) return Optional.empty();
    var op = found.get();
    if (!actor.id().equals(op.get("actor_id"))
        || !action.equals(op.get("action"))
        || !RequestFingerprints.of(json, body).equals(op.get("fingerprint")))
      throw WorkAccessService.conflict("Request ID was used for another actor, action or payload.");
    return Optional.of((UUID) op.get("entity_id"));
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void operation(
      AuthenticatedUser actor, UUID scope, UUID request, String action, Object body, UUID entity) {
    conversations.operation(
        scope, request, actor.id(), action, RequestFingerprints.of(json, body), entity);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void finish(
      AuthenticatedUser actor,
      ConversationAccess a,
      UUID request,
      String action,
      Object body,
      UUID entity,
      boolean notify) {
    operation(actor, a.conversation().id(), request, action, body, entity);
    conversations.touch(a.conversation().id(), clock.instant());
    UUID event =
        conversations.event(
            a.conversation().id(),
            actor.id(),
            action,
            entity,
            "Conversation activity: " + action.toLowerCase(Locale.ROOT).replace('_', ' ') + ".",
            true,
            clock.instant());
    audit.record(actor.id(), a.organizationId(), "CONVERSATION_" + action, entity);
    if (notify)
      notifications.conversationChanged(
          actor.id(),
          a.conversation().id(),
          event,
          action,
          conversations.recipients(a.conversation().id()),
          null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void personal(
      AuthenticatedUser actor, ConversationAccess a, UUID request, String action, Object body) {
    operation(actor, a.conversation().id(), request, action, body, a.conversation().id());
    audit.record(actor.id(), a.organizationId(), "CONVERSATION_" + action, a.conversation().id());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void moderated(AuthenticatedUser actor, Conversation c, UUID message) {
    conversations.touch(c.id(), clock.instant());
    UUID event =
        conversations.event(
            c.id(),
            actor.id(),
            "MESSAGE_MODERATED",
            message,
            "A message was withheld after moderation.",
            true,
            clock.instant());
    notifications.conversationChanged(
        actor.id(), c.id(), event, "MESSAGE_MODERATED", conversations.recipients(c.id()), null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void reportEvent(
      AuthenticatedUser actor, Conversation c, UUID report, String action, List<UUID> recipients) {
    UUID event =
        conversations.event(
            c.id(),
            actor.id(),
            action,
            report,
            "A reported message case was updated.",
            false,
            clock.instant());
    notifications.conversationChanged(
        actor.id(), c.id(), event, action, recipients, "/api/v1/me/conversation-reports/" + report);
  }
}
