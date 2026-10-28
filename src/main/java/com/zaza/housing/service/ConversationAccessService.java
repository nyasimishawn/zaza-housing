package com.zaza.housing.service;

import com.zaza.housing.enums.ConversationRole;
import com.zaza.housing.model.AuthenticatedUser;
import com.zaza.housing.model.Conversation;
import com.zaza.housing.model.ConversationAccess;
import com.zaza.housing.repository.ConversationRepository;
import com.zaza.housing.repository.OrganizationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ConversationAccessService {
  private final ConversationRepository conversations;
  private final PropertyService properties;
  private final OrganizationRepository organizations;

  public ConversationAccessService(
      ConversationRepository conversations,
      PropertyService properties,
      OrganizationRepository organizations) {
    this.conversations = conversations;
    this.properties = properties;
    this.organizations = organizations;
  }

  public ConversationAccess get(AuthenticatedUser actor, UUID id, boolean lock) {
    var a =
        conversations
            .access(actor.id(), id)
            .orElseThrow(() -> WorkAccessService.missing("Conversation not found."));
    if (lock) {
      if (a.role() == ConversationRole.MANAGER) {
        organizations.lock(a.organizationId());
        var member=organizations.member(a.organizationId(),actor.id()).orElseThrow(()->WorkAccessService.missing("Conversation not found."));
        if(member.role()==com.zaza.housing.enums.MembershipRole.MEMBER)properties.lockForApplication(a.conversation().propertyId());
        else properties.lockForManagement(actor, a.organizationId(), a.conversation().propertyId());
      }
      else {
        properties.lockForApplication(a.conversation().propertyId());
        if (a.role() == ConversationRole.PROVIDER) organizations.lock(a.organizationId());
      }
      a =
          conversations
              .access(actor.id(), id)
              .orElseThrow(() -> WorkAccessService.missing("Conversation not found."));
    }
    return a;
  }

  public Conversation moderationLock(UUID id) {
    var c =
        conversations
            .find(id)
            .orElseThrow(() -> WorkAccessService.missing("Conversation not found."));
    properties.lockForApplication(c.propertyId());
    return conversations.find(id).orElseThrow();
  }

  public void open(Conversation c) {
    if (!c.status().equals("OPEN"))
      throw WorkAccessService.conflict(
          "Reopen the conversation before sending messages or scheduling a viewing.");
  }

  public void version(Conversation c, long expected) {
    if (c.version() != expected)
      throw WorkAccessService.conflict("The conversation changed. Reload before continuing.");
  }

  public void managerOrCustomer(ConversationAccess a) {
    if (a.role() == ConversationRole.PROVIDER)
      throw WorkAccessService.forbidden(
          "Only the customer or property manager can close or reopen a conversation.");
  }

  public void operator(AuthenticatedUser actor) {
    if (!"PLATFORM_ADMIN".equals(actor.platformRole()))
      throw WorkAccessService.forbidden("Platform moderation permission is required.");
  }
}
