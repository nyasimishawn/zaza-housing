package com.zaza.housing.dto;

import com.zaza.housing.enums.LeaseParty;
import java.time.Instant;
import java.util.UUID;

public record LeaseSignatureResponse(UUID id, LeaseParty party, UUID signerId, String typedName, String email, String documentHash, String method, Instant signedAt) { }
