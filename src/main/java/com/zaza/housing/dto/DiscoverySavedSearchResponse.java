package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoverySavedSearchResponse(
    UUID id,
    String name,
    DiscoveryQuery query,
    String frequency,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Instant lastScanAt,
    Instant nextScanAt,
    String lastError) {}
