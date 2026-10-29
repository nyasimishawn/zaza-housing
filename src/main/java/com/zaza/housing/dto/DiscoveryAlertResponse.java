package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryAlertResponse(
    UUID id,
    UUID searchId,
    String searchName,
    String kind,
    UUID targetId,
    Instant createdAt,
    Instant readAt,
    boolean available,
    DiscoveryItemResponse item) {}
