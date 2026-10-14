package com.zaza.housing.organization;

import java.util.UUID;

public record Member(UUID userId, String email, String displayName, MembershipRole role) { }
