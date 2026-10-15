package com.zaza.housing.dto;

import com.zaza.housing.enums.ConversationChannel;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record OpenConversationRequest(
    @NotNull UUID requestId, @NotNull ConversationChannel channel) {}
