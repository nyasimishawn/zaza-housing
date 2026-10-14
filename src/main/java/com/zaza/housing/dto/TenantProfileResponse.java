package com.zaza.housing.dto;

import java.time.Instant;

public record TenantProfileResponse(TenantProfileDetails details, long version, Instant createdAt, Instant updatedAt) { }
