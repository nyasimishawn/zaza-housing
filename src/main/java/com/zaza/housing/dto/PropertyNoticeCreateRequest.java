package com.zaza.housing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public record PropertyNoticeCreateRequest(
    @NotNull UUID requestId,
    @NotNull UUID leaseId,
    @NotBlank @Pattern(regexp = "INSPECTION|MOVE_IN|MOVE_OUT|GENERAL") String kind,
    @NotBlank @Size(max = 150) String title,
    @NotBlank @Size(min = 20, max = 4000) String message,
    Instant startsAt,
    Instant endsAt) {}
