package com.zaza.housing.model;

import com.zaza.housing.dto.AgencyClientDetails;
import java.time.Instant;
import java.util.UUID;

public record AgencyClient(
    UUID id,
    UUID agencyId,
    UUID customerUserId,
    AgencyClientDetails details,
    String status,
    long version,
    Instant createdAt,
    Instant updatedAt) {}
