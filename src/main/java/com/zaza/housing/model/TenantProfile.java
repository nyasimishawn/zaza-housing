package com.zaza.housing.model;

import com.zaza.housing.dto.TenantProfileDetails;
import java.time.Instant;
import java.util.UUID;

public record TenantProfile(UUID userId, TenantProfileDetails details, long version, Instant createdAt, Instant updatedAt) { }
