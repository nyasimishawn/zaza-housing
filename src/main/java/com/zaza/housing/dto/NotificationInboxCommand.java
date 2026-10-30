package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.time.Instant;

public record NotificationInboxCommand(@NotNull Instant before) {}
