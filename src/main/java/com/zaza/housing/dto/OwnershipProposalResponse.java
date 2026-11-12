package com.zaza.housing.dto;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
public record OwnershipProposalResponse(UUID id,UUID propertyId,String propertyName,UUID fromOrganizationId,UUID toOrganizationId,
    String kind,BigDecimal sharePercent,long propertyVersion,UUID requesterId,String status,String document,String documentHash,String reason,
    List<LeaseSignatureResponse> consents,Instant createdAt) { }
