package com.zaza.housing.organization;

import java.time.Instant;
import java.util.UUID;

public record Organization(UUID id, String name, OrganizationType type, MembershipRole role, Instant createdAt) { }
