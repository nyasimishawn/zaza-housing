package com.zaza.housing.dto;

import com.zaza.housing.model.TeamInvitation;
import java.time.Instant;
import java.util.UUID;

public record TeamInvitationResponse(
    UUID id,
    UUID organizationId,
    String organizationName,
    String email,
    String role,
    String status,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Instant expiresAt,
    int sendCount,
    String deliveryStatus) {
  public static TeamInvitationResponse of(TeamInvitation i, Instant now) {
    return new TeamInvitationResponse(
        i.id(),
        i.organizationId(),
        i.organizationName(),
        i.email(),
        i.role(),
        i.status().equals("PENDING") && !i.expiresAt().isAfter(now) ? "EXPIRED" : i.status(),
        i.version(),
        i.createdAt(),
        i.updatedAt(),
        i.expiresAt(),
        i.sendCount(),
        i.deliveryStatus());
  }
}
