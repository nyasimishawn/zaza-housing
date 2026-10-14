package com.zaza.housing.dto;

import com.zaza.housing.enums.ApplicationActorType;
import com.zaza.housing.enums.ApplicationEventType;
import com.zaza.housing.enums.ApplicationStatus;
import java.time.Instant;
import java.util.UUID;

public record ApplicationEventResponse(UUID id, ApplicationEventType type, ApplicationStatus fromStatus, ApplicationStatus toStatus,
        ApplicationActorType actorType, String message, Instant createdAt) { }
