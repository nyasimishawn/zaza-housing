package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryComparisonResponse(
    UUID id,
    String name,
    List<UUID> listingIds,
    long version,
    Instant createdAt,
    Instant updatedAt,
    List<DiscoveryComparisonRow> rows,
    boolean comparableCurrency) {}
