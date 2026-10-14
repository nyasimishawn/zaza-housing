package com.zaza.housing.dto;

import com.zaza.housing.enums.MembershipRole;
import jakarta.validation.constraints.NotNull;

public record ChangeMemberRoleRequest(@NotNull MembershipRole role) { }
