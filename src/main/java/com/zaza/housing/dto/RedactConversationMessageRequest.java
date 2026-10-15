package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record RedactConversationMessageRequest(
    @NotNull UUID requestId, @NotBlank @Size(max = 2000) String reason) {}
