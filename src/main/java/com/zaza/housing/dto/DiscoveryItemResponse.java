package com.zaza.housing.dto;

import jakarta.validation.constraints.*;
import java.math.*;
import java.time.*;
import java.util.*;

public record DiscoveryItemResponse(
    UUID id,
    String kind,
    long version,
    PublicListingResponse listing,
    PublicServiceProviderResponse provider,
    Double latitude,
    Double longitude,
    String locationPrecision,
    Double distanceKm,
    List<String> categories,
    VerificationSummaryResponse verification,
    List<String> amenities) {}
