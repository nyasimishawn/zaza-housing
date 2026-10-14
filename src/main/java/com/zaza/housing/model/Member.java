package com.zaza.housing.model;

import com.zaza.housing.enums.MembershipRole;
import java.util.UUID;

public record Member(UUID userId, String email, String displayName, MembershipRole role) { }
