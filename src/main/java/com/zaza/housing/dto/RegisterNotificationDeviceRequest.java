package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.util.UUID;

public record RegisterNotificationDeviceRequest(
    @NotNull UUID installationId,
    @NotBlank @Pattern(regexp = "ANDROID|IOS") String platform,
    @NotBlank @Size(max = 80) String label,
    @NotBlank @Size(min = 20, max = 4096) String token) {}
