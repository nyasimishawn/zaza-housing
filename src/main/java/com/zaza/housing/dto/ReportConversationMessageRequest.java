package com.zaza.housing.dto;

import com.zaza.housing.enums.ConversationReportCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record ReportConversationMessageRequest(
    @NotNull UUID requestId,
    @NotNull ConversationReportCategory category,
    @NotBlank @Size(max = 2000) String reason) {}
