package com.zaza.housing.model;

import com.zaza.housing.enums.MembershipRole;
import com.zaza.housing.enums.OrganizationType;
import java.time.Instant;
import java.util.UUID;

public record Organization(UUID id, String name, OrganizationType type, MembershipRole role, Instant createdAt) { }
