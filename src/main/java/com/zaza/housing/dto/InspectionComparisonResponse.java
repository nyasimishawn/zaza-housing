package com.zaza.housing.dto;

import java.util.List;
import java.util.UUID;

public record InspectionComparisonResponse(
    UUID moveInInspectionId,
    UUID moveInRevisionId,
    UUID moveOutInspectionId,
    UUID moveOutRevisionId,
    String currency,
    List<InspectionComparisonItem> items) {}
