package com.zaza.housing.dto;

import com.zaza.housing.enums.LeaseEventType;
import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.enums.LeaseStatus;
import java.time.Instant;
import java.util.UUID;

public record LeaseEventResponse(UUID id, LeaseEventType type, LeaseStatus status, UUID revisionId, LeaseParty actorParty, String message, Instant createdAt) { }
