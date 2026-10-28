package com.zaza.housing.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryCommandRequest(
    @NotNull UUID requestId,
    @PositiveOrZero Long version,
    @Size(max = 100) String name,
    String kind,
    UUID targetId,
    @Size(max = 80) String folder,
    @Size(max = 1000) String note,
    @Size(min = 1, max = 4) List<@NotNull UUID> listingIds,
    String frequency,
    @Valid DiscoveryQuery query) {}
