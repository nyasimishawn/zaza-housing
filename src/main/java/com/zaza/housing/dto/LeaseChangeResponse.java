package com.zaza.housing.dto;
import com.zaza.housing.enums.LeaseParty;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record LeaseChangeResponse(UUID id, LeaseChangeTerms terms, LeaseParty requestedBy, String status,
    String documentHash, List<LeaseSignatureResponse> consents, Instant createdAt) { }
