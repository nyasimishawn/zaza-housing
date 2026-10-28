package com.zaza.housing.dto;

import java.util.UUID;

/** Frozen claim identity; private to the owning organization and assigned reviewer. */
public record PropertyOwnershipSubject(
    UUID propertyId,
    UUID ownerOrganizationId,
    String organizationName,
    String propertyName,
    String address,
    String city,
    String countryCode) {}
