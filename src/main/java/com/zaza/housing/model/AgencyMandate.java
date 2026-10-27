package com.zaza.housing.model;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
public record AgencyMandate(UUID id,UUID ownerId,String ownerName,UUID agencyId,String agencyName,UUID propertyId,long propertyVersion,String propertyName,Map<String,Object> terms,Instant startsAt,Instant endsAt,String status,boolean effective,long version,UUID requestedBy,UUID acceptedBy,Instant acceptedAt,Instant closedAt,Instant createdAt,Instant updatedAt) {}
