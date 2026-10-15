package com.zaza.housing.dto;

import com.zaza.housing.enums.ProviderStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ProviderStateRequest(@Min(0) long version, @NotNull ProviderStatus status) {}
