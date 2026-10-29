package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoverySavedItemResponse(
    UUID id,
    String kind,
    UUID targetId,
    String folder,
    String note,
    long version,
    Instant createdAt,
    Instant updatedAt,
    boolean available,
    DiscoveryItemResponse item) {}
