package com.zaza.housing.dto;

import com.zaza.housing.enums.MembershipRole;
import jakarta.validation.constraints.*;
import java.util.UUID;

public record CreateTeamInvitationRequest(
    @NotNull UUID requestId,
    @NotBlank @Email @Size(max = 254) String email,
    @NotNull MembershipRole role) {}
