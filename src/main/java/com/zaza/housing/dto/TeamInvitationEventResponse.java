package com.zaza.housing.dto;

import java.time.Instant;
import java.util.UUID;

public record TeamInvitationEventResponse(UUID id, String action, Instant createdAt) {}
