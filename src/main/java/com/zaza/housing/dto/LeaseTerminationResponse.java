package com.zaza.housing.dto;

import com.zaza.housing.enums.LeaseParty;
import com.zaza.housing.enums.LeaseTerminationStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record LeaseTerminationResponse(UUID id, LeaseParty requestedBy, LocalDate proposedEndDate, String message, String documentHash,
        LeaseTerminationStatus status, List<LeaseSignatureResponse> consents, Instant createdAt) { }
