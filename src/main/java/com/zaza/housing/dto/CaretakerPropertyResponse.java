package com.zaza.housing.dto;

import com.zaza.housing.enums.CaretakerCapability;
import java.util.Set;
import java.util.UUID;

public record CaretakerPropertyResponse(
    UUID id,
    String name,
    String address,
    String city,
    String countryCode,
    String status,
    long version,
    Set<CaretakerCapability> capabilities) {}
