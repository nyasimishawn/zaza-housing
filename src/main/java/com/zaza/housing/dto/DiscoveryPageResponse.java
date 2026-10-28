package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryPageResponse(
    List<DiscoveryItemResponse> items,
    int limit,
    int offset,
    boolean hasMore,
    int mappedCount,
    int unmappedCount,
    String locationNotice) {}
