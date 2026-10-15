package com.zaza.housing.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record ConversationPreferenceRequest(@NotNull UUID requestId, boolean muted) {}
