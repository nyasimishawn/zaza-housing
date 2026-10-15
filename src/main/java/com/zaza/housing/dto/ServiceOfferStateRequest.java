package com.zaza.housing.dto;

import com.zaza.housing.enums.ServiceOfferStatus;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ServiceOfferStateRequest(@Min(0) long version, @NotNull ServiceOfferStatus status) {}
