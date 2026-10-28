package com.zaza.housing.dto;

import java.util.UUID;

public record AgencyEnquiryResponse(UUID id, UUID propertyId, UUID listingId, String subject) {}
