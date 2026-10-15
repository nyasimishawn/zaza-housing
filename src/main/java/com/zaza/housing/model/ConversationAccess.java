package com.zaza.housing.model;

import com.zaza.housing.enums.ConversationRole;
import java.util.UUID;

public record ConversationAccess(
    Conversation conversation, ConversationRole role, UUID organizationId) {}
