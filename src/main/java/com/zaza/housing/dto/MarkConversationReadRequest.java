package com.zaza.housing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record MarkConversationReadRequest(@NotNull UUID requestId, @Min(0) long throughSequence) {}
